package io.github.woodypl.doubletake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dtcore.mobile.Listener
import dtcore.mobile.Mobile
import dtcore.mobile.Session
import java.io.File

/**
 * Foreground service that owns one mirroring session: the MediaProjection,
 * the H.264 encoder and its virtual display, playback-audio capture, and the
 * Go AirPlay session they feed.
 */
class MirrorService : Service() {
    companion object {
        const val ACTION_START = "io.github.woodypl.doubletake.START"
        const val ACTION_STOP = "io.github.woodypl.doubletake.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_DEBUG = "debug"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_LATENCY = "latency"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_FORCE_PAIR = "forcePair"

        private const val CHANNEL_ID = "mirroring"
        private const val NOTIFICATION_ID = 1

        fun credentialsFile(context: Context) = File(context.filesDir, "credentials.json")
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var session: Session? = null
    private var encoder: VideoEncoder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var audio: AudioCapturer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var bitrateKbps = 8000
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                MirrorController.log("stop requested")
                stopMirroring()
            }
            ACTION_START -> {
                if (session != null) {
                    MirrorController.log("session already running; ignoring start")
                } else {
                    startMirroring(intent)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startMirroring(intent: Intent) {
        createChannel()
        // Android 14+: the service must be in the foreground before the
        // projection token is redeemed.
        startForeground(NOTIFICATION_ID, notification("Connecting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = resultData?.let { manager.getMediaProjection(resultCode, it) }
        if (mp == null) {
            MirrorController.setState(Mobile.StateFailed, "screen capture permission was not granted")
            stopSelf()
            return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                MirrorController.log("screen capture ended by the system or user")
                main.post { stopMirroring() }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                MirrorController.log("captured content resized to ${width}x$height")
            }
        }, main)

        acquireLocks()

        bitrateKbps = intent.getIntExtra(EXTRA_BITRATE, 8000)
        val config = Mobile.newConfig(intent.getStringExtra(EXTRA_HOST) ?: "").apply {
            port = intent.getIntExtra(EXTRA_PORT, 7000).toLong()
            credentialsPath = credentialsFile(this@MirrorService).absolutePath
            fps = 30
            this.bitrateKbps = this@MirrorService.bitrateKbps.toLong()
            targetLatencyMs = intent.getIntExtra(EXTRA_LATENCY, 0).toLong()
            noAudio = !intent.getBooleanExtra(EXTRA_AUDIO, true)
            debug = intent.getBooleanExtra(EXTRA_DEBUG, false)
            forcePair = intent.getBooleanExtra(EXTRA_FORCE_PAIR, false)
            initialVolume = intent.getStringExtra(EXTRA_VOLUME) ?: "keep"
        }
        MirrorController.log("starting session to ${config.host}:${config.port} " +
            "(audio=${!config.noAudio}, debug=${config.debug}, bitrate=${bitrateKbps}kbps, " +
            "latency=${if (config.targetLatencyMs == 0L) "auto" else "${config.targetLatencyMs}ms"}, forcePair=${config.forcePair}, volume=${config.initialVolume})")

        val s = Mobile.newSession(config, listener)
        session = s
        MirrorController.codeHandler = { code -> s.submitCode(code) }
        s.start()
        main.postDelayed(statsLogger, 5000)
    }

    private val listener = object : Listener {
        override fun onState(state: String, detail: String) {
            MirrorController.setState(state, detail)
            val text = when (state) {
                Mobile.StateConnecting -> "Connecting…"
                Mobile.StatePairing -> "Pairing…"
                Mobile.StateNeedCode -> if (detail == Mobile.CodePIN) "Enter the PIN shown on the projector" else "Enter the receiver password"
                Mobile.StateSettingUp -> "Setting up…"
                Mobile.StateStreaming -> "Mirroring"
                else -> null
            }
            if (text != null) {
                main.post {
                    if (!stopping) getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(text))
                }
            }
            if (state == Mobile.StateStopped || state == Mobile.StateFailed) {
                main.post { stopMirroring() }
            }
        }

        override fun onVideoFormat(width: Long, height: Long, fps: Long) {
            main.post { startVideo(width.toInt(), height.toInt(), fps.toInt()) }
        }

        override fun onKeyframeNeeded() {
            encoder?.requestKeyframe()
        }

        override fun onAudioFormat(sampleRate: Long, channels: Long) {
            main.post { startAudio() }
        }
    }

    private fun startVideo(width: Int, height: Int, fps: Int) {
        val mp = projection ?: return
        val s = session ?: return
        if (encoder != null) return
        try {
            val enc = VideoEncoder(width, height, fps, bitrateKbps) { data, keyframe, age ->
                try {
                    s.pushVideo(data, keyframe, age)
                } catch (_: Exception) {
                    // Session closed; the state callback tears everything down.
                }
            }
            enc.start()
            encoder = enc
            virtualDisplay = mp.createVirtualDisplay(
                "doubletake", width, height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                enc.inputSurface, null, null)
            MirrorController.log("virtual display ${width}x$height @${resources.displayMetrics.densityDpi}dpi created")
        } catch (e: Exception) {
            MirrorController.log("failed to start video: $e")
            MirrorController.setState(Mobile.StateFailed, "video encoder: ${e.message}")
            stopMirroring()
        }
    }

    private fun startAudio() {
        val mp = projection ?: return
        val s = session ?: return
        if (audio != null) return
        try {
            val capturer = AudioCapturer(mp) { pcm, age ->
                try {
                    s.pushAudio(pcm, age)
                } catch (_: Exception) {
                }
            }
            capturer.start()
            audio = capturer
        } catch (e: Exception) {
            MirrorController.log("audio capture unavailable, continuing without sound: $e")
        }
    }

    private val statsLogger = object : Runnable {
        override fun run() {
            if (stopping) return
            val enc = encoder
            MirrorController.log("stats: video frames=${enc?.framesOut ?: 0} bytes=${enc?.bytesOut ?: 0}, " +
                "audio chunks=${audio?.chunksOut ?: 0}")
            main.postDelayed(this, 10_000)
        }
    }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "doubletake:mirror").apply {
            setReferenceCounted(false)
            acquire()
        }
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "doubletake:mirror").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun stopMirroring() {
        if (stopping) return
        stopping = true
        main.removeCallbacks(statsLogger)
        MirrorController.codeHandler = null
        val s = session
        session = null
        // Session.stop waits for the Go side to wind down; keep it off the main thread.
        Thread({
            s?.stop()
            main.post { releaseMedia() }
        }, "session-stop").start()
    }

    private fun releaseMedia() {
        virtualDisplay?.release()
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        audio?.stop()
        audio = null
        projection?.stop()
        projection = null
        wifiLock?.release()
        wifiLock = null
        wakeLock?.release()
        wakeLock = null
        if (MirrorController.isActive) MirrorController.setState(Mobile.StateStopped, "")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) stopMirroring()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mirroring", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Doubletake")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
