package app.scrcpy.scrcpy

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import app.scrcpy.adb.AdbStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audio pipeline: scrcpy audio packets → (MediaCodec | direct PCM) →
 * low-latency AudioTrack at 48 kHz stereo.
 *
 * Codec id 0 means the server disabled audio (target below Android 11);
 * id 1 means a server-side configuration error. Both are logged and the
 * player simply stays silent.
 */
class AudioPlayer(
    private val stream: AdbStream,
    private val codecId: Int
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var codec: MediaCodec? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ runLoop() }, "scrcpy-audio")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun pause() {
        try {
            audioTrack?.pause()
        } catch (_: Throwable) {
        }
    }

    fun resume() {
        try {
            audioTrack?.play()
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        running.set(false)
        try {
            stream.close()
        } catch (_: Throwable) {
        }
        releaseCodec()
        try {
            audioTrack?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioTrack?.release()
        } catch (_: Throwable) {
        }
        audioTrack = null
    }

    private fun runLoop() {
        when (codecId) {
            0 -> {
                Log.i(TAG, "Audio disabled by server (requires Android 11+ on the target)")
                return
            }
            1 -> {
                Log.w(TAG, "Audio error on server (configuration error)")
                return
            }
        }
        if (codecId == ScrcpyProtocol.CODEC_RAW) {
            runRaw()
        } else {
            runCompressed()
        }
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = builder.build()
        track.play()
        audioTrack = track
        return track
    }

    private fun runRaw() {
        val track = createTrack()
        val input = DataInputStream(stream.input)
        try {
            while (running.get() && !stream.isClosed) {
                input.readLong() // PTS + flags, unused for raw playback
                val size = input.readInt()
                if (size <= 0 || size > MAX_PACKET_SIZE) continue
                val data = ByteArray(size)
                input.readFully(data)
                track.write(data, 0, data.size)
            }
        } catch (t: Throwable) {
            if (running.get()) Log.w(TAG, "Raw audio loop ended: ${t.message}")
        }
    }

    private fun runCompressed() {
        val mime = when (codecId) {
            ScrcpyProtocol.CODEC_OPUS -> "audio/opus"
            ScrcpyProtocol.CODEC_AAC -> "audio/mp4a-latm"
            ScrcpyProtocol.CODEC_FLAC -> "audio/flac"
            else -> {
                Log.e(TAG, "Unsupported audio codec 0x${codecId.toString(16)}")
                return
            }
        }
        val input = DataInputStream(stream.input)
        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, CHANNELS)

        val config = readFirstConfigPacket(input)
        if (config != null) {
            if (codecId == ScrcpyProtocol.CODEC_OPUS) {
                setupOpusCsd(format, config)
            } else {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
            }
        } else {
            Log.w(TAG, "No config packet received, decoder may fail")
        }

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        codec = decoder
        val track = createTrack()
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get() && !stream.isClosed) {
                val ptsFlags = input.readLong()
                val size = input.readInt()
                if (size <= 0 || size > MAX_PACKET_SIZE) continue
                val data = ByteArray(size)
                input.readFully(data)
                val isConfig = (ptsFlags and Long.MIN_VALUE) != 0L
                if (isConfig) continue
                feedAndDrain(decoder, data, ptsFlags and PTS_MASK, track, bufferInfo)
            }
        } catch (t: Throwable) {
            if (running.get()) Log.w(TAG, "Audio loop ended: ${t.message}")
        }
    }

    private fun readFirstConfigPacket(input: DataInputStream): ByteArray? {
        // The first packet is normally the config packet; scan a few in case
        // the stream starts differently, buffering nothing else.
        repeat(10) {
            val ptsFlags = input.readLong()
            val size = input.readInt()
            if (size <= 0 || size > MAX_PACKET_SIZE) return null
            val data = ByteArray(size)
            input.readFully(data)
            if ((ptsFlags and Long.MIN_VALUE) != 0L) return data
        }
        return null
    }

    private fun setupOpusCsd(format: MediaFormat, config: ByteArray) {
        // csd-0: the OpusHead identification header (from the config packet).
        format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
        // preSkip lives at bytes 10..11 of OpusHead (little-endian samples).
        var preSkip = 312
        if (config.size >= 12 && config.copyOfRange(0, 8).contentEquals(OPUS_HEAD_MAGIC)) {
            preSkip = (config[10].toInt() and 0xFF) or ((config[11].toInt() and 0xFF) shl 8)
        }
        val delayNs = preSkip.toLong() * 1_000_000_000L / SAMPLE_RATE
        val csd1 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(delayNs)
        val csd2 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(80_000_000L)
        format.setByteBuffer("csd-1", csd1)
        format.setByteBuffer("csd-2", csd2)
    }

    private fun feedAndDrain(
        decoder: MediaCodec,
        data: ByteArray,
        pts: Long,
        track: AudioTrack,
        info: MediaCodec.BufferInfo
    ) {
        try {
            val inIndex = decoder.dequeueInputBuffer(INPUT_WAIT_US)
            if (inIndex >= 0) {
                val buffer = decoder.getInputBuffer(inIndex)
                buffer?.clear()
                buffer?.put(data)
                decoder.queueInputBuffer(inIndex, 0, data.size, pts, 0)
            }
            while (true) {
                val outIndex = decoder.dequeueOutputBuffer(info, 0)
                when {
                    outIndex >= 0 -> {
                        val outBuffer = decoder.getOutputBuffer(outIndex)
                        if (outBuffer != null && info.size > 0) {
                            val pcm = ByteArray(info.size)
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)
                            outBuffer.get(pcm)
                            track.write(pcm, 0, pcm.size)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    else -> break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "audio decode failed: ${t.message}")
        }
    }

    private fun releaseCodec() {
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
    }

    companion object {
        private const val TAG = "ScrcpyAudio"
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val MAX_PACKET_SIZE = 1024 * 1024
        private const val INPUT_WAIT_US = 10_000L
        private const val PTS_MASK = 0x3FFFFFFFFFFFFFFFL
        private val OPUS_HEAD_MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)
    }
}
