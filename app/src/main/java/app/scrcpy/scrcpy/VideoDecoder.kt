package app.scrcpy.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import app.scrcpy.adb.AdbStream
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-latency video pipeline: scrcpy packet stream → MediaCodec → Surface.
 *
 * Latency rules applied here:
 *  - the decoder is created with the low-latency hint and real-time
 *    priority, and its operating rate is set to the requested frame cap;
 *  - packets are fed as soon as they arrive and output buffers are always
 *    rendered immediately (never queued client-side);
 *  - if the decoder momentarily has no input buffer for an ordinary frame,
 *    the frame is dropped instead of letting a backlog build up — a fresh
 *    frame is always worth more than a stale one;
 *  - the display surface itself is told to prefer the session frame rate.
 */
class VideoDecoder(
    private val stream: AdbStream,
    private val codecId: Int,
    private val width: Int,
    private val height: Int,
    private val maxFps: Int,
    private val onStats: ((fps: Int, kbps: Int) -> Unit)? = null
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var codec: MediaCodec? = null
    private val codecLock = Any()

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var lastConfig: ByteArray? = null

    val framesDecoded = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)

    fun start(targetSurface: Surface) {
        surface = targetSurface
        applySurfaceFrameRate(targetSurface)
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ decodeLoop() }, "scrcpy-video")
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
    }

    /** Rebuilds the codec on a new surface (rotation, window recreation). */
    fun restartCodec(newSurface: Surface) {
        surface = newSurface
        applySurfaceFrameRate(newSurface)
        synchronized(codecLock) {
            releaseCodecLocked()
            val created = createCodec(newSurface) ?: return
            codec = created
            lastConfig?.let { config ->
                feed(created, config, 0L, isConfig = true, isKeyFrame = false, waitForBuffer = true)
            }
        }
    }

    fun releaseCodec() {
        synchronized(codecLock) { releaseCodecLocked() }
    }

    fun stop() {
        running.set(false)
        try {
            stream.close()
        } catch (_: Throwable) {
        }
        thread?.interrupt()
        synchronized(codecLock) { releaseCodecLocked() }
    }

    // -------------------------------------------------------------- internal

    private fun mimeType(): String? = when (codecId) {
        ScrcpyProtocol.CODEC_H264 -> "video/avc"
        ScrcpyProtocol.CODEC_H265 -> "video/hevc"
        ScrcpyProtocol.CODEC_AV1 -> "video/av01"
        else -> null
    }

    private fun applySurfaceFrameRate(s: Surface) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                s.setFrameRate(maxFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (_: Throwable) {
            }
        }
    }

    private fun createCodec(target: Surface): MediaCodec? {
        val mime = mimeType() ?: run {
            Log.e(TAG, "Unsupported video codec 0x${codecId.toString(16)}")
            return null
        }
        val format = MediaFormat.createVideoFormat(mime, width, height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, maxFps)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
        val created = MediaCodec.createDecoderByType(mime)
        created.configure(format, target, null, 0)
        created.start()
        return created
    }

    private fun releaseCodecLocked() {
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

    private fun decodeLoop() {
        val mime = mimeType()
        if (mime == null) {
            Log.e(TAG, "Unsupported video codec 0x${codecId.toString(16)}")
            return
        }
        val initialSurface = surface ?: return
        synchronized(codecLock) {
            releaseCodecLocked()
            codec = createCodec(initialSurface)
        }

        val input = DataInputStream(stream.input)
        val bufferInfo = MediaCodec.BufferInfo()
        var statWindowStart = System.nanoTime()
        var statFrames = 0L
        var statBytes = 0L
        try {
            while (running.get() && !stream.isClosed) {
                val ptsFlags = input.readLong()
                val size = input.readInt()
                if (size <= 0 || size > MAX_PACKET_SIZE) {
                    Log.w(TAG, "Invalid packet size $size, skipping")
                    continue
                }
                val data = ByteArray(size)
                input.readFully(data)
                bytesReceived.addAndGet(size.toLong() + 12)
                statBytes += size.toLong()

                val isConfig = (ptsFlags and Long.MIN_VALUE) != 0L
                val isKeyFrame = (ptsFlags and (1L shl 62)) != 0L
                val pts = ptsFlags and PTS_MASK
                if (isConfig) lastConfig = data.copyOf()

                synchronized(codecLock) {
                    val active = codec
                    if (active != null) {
                        feed(active, data, pts, isConfig, isKeyFrame, waitForBuffer = isConfig || isKeyFrame)
                        drain(active, bufferInfo)
                    }
                }
                statFrames++
                val now = System.nanoTime()
                val elapsedMs = (now - statWindowStart) / 1_000_000
                if (elapsedMs >= 1000) {
                    val fps = (statFrames * 1000 / elapsedMs).toInt()
                    val kbps = (statBytes * 8 / elapsedMs).toInt()
                    onStats?.invoke(fps, kbps)
                    statWindowStart = now
                    statFrames = 0
                    statBytes = 0
                }
            }
        } catch (t: Throwable) {
            if (running.get()) Log.w(TAG, "Video loop ended: ${t.message}")
        }
    }

    private fun feed(
        codec: MediaCodec,
        data: ByteArray,
        pts: Long,
        isConfig: Boolean,
        isKeyFrame: Boolean,
        waitForBuffer: Boolean
    ) {
        try {
            var index = codec.dequeueInputBuffer(if (waitForBuffer) INPUT_WAIT_US else 0)
            if (index < 0 && !waitForBuffer) return // drop: never build a backlog
            if (index < 0) index = codec.dequeueInputBuffer(-1)
            if (index < 0) return
            val inputBuffer = codec.getInputBuffer(index) ?: return
            inputBuffer.clear()
            inputBuffer.put(data)
            val flags = when {
                isConfig -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                isKeyFrame -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                else -> 0
            }
            codec.queueInputBuffer(index, 0, data.size, pts, flags)
        } catch (t: Throwable) {
            Log.w(TAG, "feed failed: ${t.message}")
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        try {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                when {
                    index >= 0 -> {
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        codec.releaseOutputBuffer(index, !isConfig)
                        if (!isConfig) framesDecoded.incrementAndGet()
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    else -> break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "drain failed (surface gone?): ${t.message}")
            synchronized(codecLock) { releaseCodecLocked() }
        }
    }

    companion object {
        private const val TAG = "ScrcpyVideo"
        private const val MAX_PACKET_SIZE = 8 * 1024 * 1024
        private const val INPUT_WAIT_US = 10_000L
        private const val PTS_MASK = 0x3FFFFFFFFFFFFFFFL
    }
}
