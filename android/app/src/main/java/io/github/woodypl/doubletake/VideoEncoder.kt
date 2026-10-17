package io.github.woodypl.doubletake

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder fed through an input Surface. Every output access
 * unit is delivered in Annex-B form; keyframes are prefixed with the cached
 * SPS/PPS so the receiver can start decoding at any IDR.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateKbps: Int,
    private val sink: (data: ByteArray, keyframe: Boolean, ageNanos: Long) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    lateinit var inputSurface: Surface
        private set
    @Volatile private var running = false
    private var thread: Thread? = null
    private var codecConfig: ByteArray? = null

    var framesOut = 0L
        private set
    var bytesOut = 0L
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Baseline matches the stream doubletake's desktop path (openh264)
            // already proved on the Freestyle, and has no B-frames.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            // A static screen produces no new surface frames; keep the stream
            // alive by repeating the last one.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            MirrorController.log("encoder rejected baseline/level 4.1 ($e); retrying with defaults")
            format.removeKey(MediaFormat.KEY_PROFILE)
            format.removeKey(MediaFormat.KEY_LEVEL)
            format.removeKey(MediaFormat.KEY_LATENCY)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        MirrorController.log("encoder ${codec.name} configured ${width}x$height@$fps ${bitrateKbps}kbps")
        inputSurface = codec.createInputSurface()
        codec.start()
        running = true
        thread = Thread(::drain, "video-encoder").apply { start() }
    }

    fun requestKeyframe() {
        if (!running) return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            // Encoder is stopping.
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 100_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index < 0) continue
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) handle(buffer, info)
            } finally {
                try { codec.releaseOutputBuffer(index, false) } catch (_: IllegalStateException) {}
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    private fun handle(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val data = ByteArray(info.size)
        buffer.get(data)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            codecConfig = data
            MirrorController.log("encoder codec config: ${data.size} bytes")
            return
        }
        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val unit = if (keyframe && codecConfig != null) codecConfig!! + data else data
        // Surface input timestamps are on the System.nanoTime() clock.
        val ageNanos = System.nanoTime() - info.presentationTimeUs * 1000
        framesOut++
        bytesOut += unit.size
        sink(unit, keyframe, ageNanos)
    }

    fun stop() {
        running = false
        try { codec.signalEndOfInputStream() } catch (_: Exception) {}
        thread?.join(500)
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        if (this::inputSurface.isInitialized) inputSurface.release()
    }
}
