package io.github.woodypl.doubletake

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.concurrent.Executor

/** A receiver found over mDNS. */
data class Receiver(val name: String, val host: String, val port: Int)

/**
 * Finds AirPlay receivers (_airplay._tcp) with Android's NsdManager, which
 * works without multicast permissions or interface enumeration.
 */
class Discovery(context: Context, private val onChange: (List<Receiver>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val found = linkedMapOf<String, Receiver>()
    private val infoCallbacks = mutableListOf<NsdManager.ServiceInfoCallback>()
    private val pendingResolves = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) =
                MirrorController.log("discovery started")
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                MirrorController.log("discovery failed to start: $errorCode")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = main.post { resolve(info) }.let {}
            override fun onServiceLost(info: NsdServiceInfo) = main.post {
                if (found.remove(info.serviceName) != null) onChange(found.values.toList())
            }.let {}
        }
        listener = l
        nsd.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) =
                    MirrorController.log("resolve ${info.serviceName} failed: $errorCode")
                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    val address = updated.hostAddresses.firstOrNull { it is Inet4Address }
                        ?: updated.hostAddresses.firstOrNull()
                    if (address != null) add(updated.serviceName, address.hostAddress ?: return, updated.port)
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            infoCallbacks += callback
            nsd.registerServiceInfoCallback(info, mainExecutor, callback)
        } else {
            pendingResolves.addLast(info)
            resolveNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = pendingResolves.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = main.post {
                resolving = false
                resolveNext()
            }.let {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = main.post {
                resolving = false
                serviceInfo.host?.hostAddress?.let { add(serviceInfo.serviceName, it, serviceInfo.port) }
                resolveNext()
            }.let {}
        })
    }

    private fun add(name: String, host: String, port: Int) {
        val receiver = Receiver(name, host, port)
        if (found[name] != receiver) {
            found[name] = receiver
            MirrorController.log("found receiver: $name at $host:$port")
            onChange(found.values.toList())
        }
    }

    fun stop() {
        listener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: IllegalArgumentException) {}
        }
        listener = null
        if (Build.VERSION.SDK_INT >= 34) {
            infoCallbacks.forEach {
                try { nsd.unregisterServiceInfoCallback(it) } catch (_: IllegalArgumentException) {}
            }
        }
        infoCallbacks.clear()
        pendingResolves.clear()
    }
}
