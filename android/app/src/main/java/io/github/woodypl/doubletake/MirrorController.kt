package io.github.woodypl.doubletake

import android.os.Handler
import android.os.Looper
import android.util.Log
import dtcore.mobile.LogSink
import dtcore.mobile.Mobile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Process-wide state shared by the UI and the mirroring service: the current
 * session state, a pending PIN/password prompt, and a bounded log.
 */
object MirrorController {
    interface Observer {
        fun onStateChanged(state: String, detail: String) {}
        fun onCodeRequested(kind: String) {}
        fun onLog(line: String) {}
    }

    private const val MAX_LOG_LINES = 5000

    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<Observer>()
    private val log = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var state: String = Mobile.StateStopped
        private set
    @Volatile var stateDetail: String = ""
        private set
    @Volatile var pendingCodeKind: String? = null
        private set

    /** Answers a code prompt; set by the service while a session runs. */
    @Volatile var codeHandler: ((String) -> Unit)? = null

    private var logsRedirected = false

    fun init() {
        if (logsRedirected) return
        logsRedirected = true
        Mobile.redirectLogs(object : LogSink {
            override fun onLog(line: String) = append(line, timestamp = false)
        })
        append("app started: ${Mobile.version()}, Android ${android.os.Build.VERSION.RELEASE} " +
            "(API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    fun addObserver(observer: Observer) = main.post { observers += observer }
    fun removeObserver(observer: Observer) = main.post { observers -= observer }

    /** Logs a line from the app side (the Go side has its own timestamps). */
    fun log(line: String) = append(line, timestamp = true)

    private fun append(line: String, timestamp: Boolean) {
        val text = if (timestamp) "${stamp.format(Date())} [APP] $line" else line
        Log.i("doubletake", text)
        synchronized(log) {
            log.addLast(text)
            while (log.size > MAX_LOG_LINES) log.removeFirst()
        }
        main.post { observers.forEach { it.onLog(text) } }
    }

    fun logText(): String = synchronized(log) { log.joinToString("\n") }

    fun recentLog(lines: Int): List<String> = synchronized(log) { log.toList().takeLast(lines) }

    fun setState(state: String, detail: String) {
        this.state = state
        this.stateDetail = detail
        if (state != Mobile.StateNeedCode) pendingCodeKind = null
        log("state: $state${if (detail.isNotEmpty()) " ($detail)" else ""}")
        main.post {
            observers.forEach { it.onStateChanged(state, detail) }
            if (state == Mobile.StateNeedCode) {
                pendingCodeKind = detail
                observers.forEach { it.onCodeRequested(detail) }
            }
        }
    }

    fun submitCode(code: String) {
        pendingCodeKind = null
        codeHandler?.invoke(code)
    }

    val isActive: Boolean
        get() = state != Mobile.StateStopped && state != Mobile.StateFailed
}
