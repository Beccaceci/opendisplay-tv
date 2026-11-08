package app.opendisplay.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import app.opendisplay.android.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Low-latency AAC audio player for OpenDisplay receiver.
 * Decodes incoming AAC-LC audio frames (48 kHz, stereo) via MediaCodec
 * and renders PCM directly to an AudioTrack in low-latency performance mode.
 */
class AudioPlayer {
    private val TAG = "OpenDisplay.Audio"
    private val SAMPLE_RATE = 48000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var running = false
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private data class AudioPacket(val ptsMs: Long, val data: ByteArray)
    private val inputQueue = LinkedBlockingQueue<AudioPacket>(64)
    private var decodeThread: Thread? = null

    fun start() {
        if (running) return
        running = true

        try {
            val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = (minBufSize * 2).coerceAtLeast(SAMPLE_RATE * 4 / 20) // ~50ms buffer

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            track.play()
            audioTrack = track

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_IS_ADTS, 0) // Raw AAC packets
                // Specific CSD-0 for 48000Hz stereo AAC-LC:
                // AudioSpecificConfig: profile=2 (LC), freq_idx=3 (48000), channel_config=2 (stereo)
                // Binary: 00010 0011 0010 000 = 0x11, 0x90
                val csd0 = ByteBuffer.wrap(byteArrayOf(0x11.toByte(), 0x90.toByte()))
                setByteBuffer("csd-0", csd0)
            }

            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            dec.configure(format, null, null, 0)
            dec.start()
            decoder = dec

            decodeThread = Thread(::decodeLoop, "od-audio-decode").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }
            Log.i(TAG, "audio player started: 48kHz stereo AAC-LC low-latency")
        } catch (e: Exception) {
            Log.w(TAG, "could not start audio player: $e")
            release()
        }
    }

    private var firstPacketLogged = false

    fun submitAudio(ptsMs: Long, aacData: ByteArray) {
        if (!running) return
        if (!firstPacketLogged) {
            firstPacketLogged = true
            Log.i(TAG, "first audio packet received on TV: ${aacData.size} bytes, pts=$ptsMs ms")
        }
        if (!inputQueue.offer(AudioPacket(ptsMs, aacData))) {
            // Queue full: drop oldest to avoid audio lag behind video
            inputQueue.poll()
            inputQueue.offer(AudioPacket(ptsMs, aacData))
        }
    }

    private fun decodeLoop() {
        val dec = decoder ?: return
        val track = audioTrack ?: return
        val info = MediaCodec.BufferInfo()

        while (running) {
            try {
                val packet = inputQueue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (packet != null) {
                    val inIndex = dec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = dec.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            inBuffer.clear()
                            inBuffer.put(packet.data)
                            dec.queueInputBuffer(inIndex, 0, packet.data.size, packet.ptsMs * 1000, 0)
                        }
                    }
                }

                var outIndex = dec.dequeueOutputBuffer(info, 0)
                while (outIndex >= 0 && running) {
                    val outBuffer = dec.getOutputBuffer(outIndex)
                    if (outBuffer != null && info.size > 0) {
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)
                        track.write(outBuffer, info.size, AudioTrack.WRITE_BLOCKING)
                    }
                    dec.releaseOutputBuffer(outIndex, false)
                    outIndex = dec.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "audio decode loop error: $e")
                break
            }
        }
    }

    fun reset() {
        inputQueue.clear()
        try {
            decoder?.flush()
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "audio reset error: $e")
        }
    }

    fun release() {
        running = false
        inputQueue.clear()
        decodeThread?.interrupt()
        decodeThread = null

        try {
            decoder?.stop()
            decoder?.release()
        } catch (_: Exception) {}
        decoder = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
