package io.github.woodypl.doubletake

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection

/**
 * Captures other apps' playback (media, games, unknown usages) as 44.1 kHz
 * stereo S16LE and hands it on with the age of each chunk's first sample.
 * Apps that opt out of capture (and DRM playback) arrive as silence.
 */
class AudioCapturer(
    private val projection: MediaProjection,
    private val sink: (pcm: ByteArray, ageNanos: Long) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 44100
        private const val BYTES_PER_FRAME = 4 // stereo, 16-bit
        // 4 AirPlay codec frames (352 samples each) per read: ~32 ms.
        private const val CHUNK_FRAMES = 352 * 4
    }

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    var chunksOut = 0L
        private set

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before the session starts.
    fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_FRAMES * BYTES_PER_FRAME * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("AudioRecord failed to initialise for playback capture")
        }
        record = audioRecord
        audioRecord.startRecording()
        running = true
        MirrorController.log("audio capture started (${audioRecord.sampleRate} Hz, buffer ${audioRecord.bufferSizeInFrames} frames)")
        thread = Thread(::loop, "audio-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop() {
        val audioRecord = record ?: return
        val buffer = ByteArray(CHUNK_FRAMES * BYTES_PER_FRAME)
        val timestamp = AudioTimestamp()
        var framesRead = 0L
        var timestampWarned = false
        while (running) {
            var filled = 0
            while (filled < buffer.size && running) {
                val n = audioRecord.read(buffer, filled, buffer.size - filled, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    MirrorController.log("AudioRecord.read error $n; stopping audio")
                    running = false
                    break
                }
                filled += n
            }
            if (filled < buffer.size) break
            val chunkStartFrame = framesRead
            framesRead += CHUNK_FRAMES
            // Prefer the HAL's frame/time correlation; fall back to assuming
            // the chunk ends now.
            val now = System.nanoTime()
            val chunkNanos = CHUNK_FRAMES * 1_000_000_000L / SAMPLE_RATE
            val halAge = if (audioRecord.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                val startTime = timestamp.nanoTime +
                    (chunkStartFrame - timestamp.framePosition) * 1_000_000_000L / SAMPLE_RATE
                (now - startTime).takeIf { it in chunkNanos / 2..1_000_000_000L }
            } else null
            val ageNanos = if (halAge != null) {
                halAge
            } else {
                if (!timestampWarned) {
                    MirrorController.log("AudioRecord timestamps unavailable or implausible; using read time")
                    timestampWarned = true
                }
                chunkNanos
            }
            chunksOut++
            sink(buffer.copyOf(), ageNanos)
        }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        thread?.join(500)
        record?.release()
        record = null
    }
}
