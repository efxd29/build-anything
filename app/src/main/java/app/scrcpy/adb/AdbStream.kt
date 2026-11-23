package app.scrcpy.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One ADB stream (a `shell:`, `sync:`, `localabstract:` … service) inside an
 * [AdbConnection].
 *
 * Flow control follows the ADB spec strictly: every A_WRTE we send must be
 * acknowledged with A_OKAY before the next chunk goes out, and every chunk
 * we receive is acknowledged immediately.
 */
class AdbStream internal constructor(
    private val connection: AdbConnection,
    val localId: Int,
    val service: String
) {
    @Volatile
    var remoteId: Int = 0
        internal set

    private val readyLatch = CountDownLatch(1)
    private val okaySemaphore = Semaphore(0)
    private val closedFlag = AtomicBoolean(false)

    private val pipeOut = PipedOutputStream()
    private val pipeIn = PipedInputStream(pipeOut, INPUT_BUFFER_SIZE)

    val isClosed: Boolean
        get() = closedFlag.get()

    /** Data sent by the device on this stream. */
    val input: InputStream
        get() = pipeIn

    /**
     * Stream to the device. Writes are split into [WRITE_CHUNK] chunks, each
     * waiting for the device's A_OKAY before continuing.
     */
    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closedFlag.get()) throw IOException("Stream is closed")
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val chunkLen = minOf(remaining, WRITE_CHUNK)
                val chunk = b.copyOfRange(offset, offset + chunkLen)
                connection.sendWrite(this@AdbStream, chunk)
                if (!okaySemaphore.tryAcquire(OKAY_TIMEOUT_S, TimeUnit.SECONDS)) {
                    throw IOException("ADB WRTE timed out waiting for OKAY")
                }
                offset += chunkLen
                remaining -= chunkLen
            }
        }

        override fun flush() {}
        override fun close() {
            this@AdbStream.close()
        }
    }

    internal fun awaitReady(timeoutMs: Long): Boolean =
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    internal fun onOkay(remoteId: Int) {
        if (this.remoteId == 0) {
            this.remoteId = remoteId
            readyLatch.countDown()
        } else {
            okaySemaphore.release()
        }
    }

    internal fun onData(payload: ByteArray) {
        if (closedFlag.get()) return
        try {
            pipeOut.write(payload)
            pipeOut.flush()
        } catch (_: IOException) {
            // Reader side went away; nothing useful to do.
        }
        connection.sendOkay(this)
    }

    internal fun onCloseFromDevice() {
        markClosed()
    }

    fun close() {
        if (closedFlag.compareAndSet(false, true)) {
            connection.sendClose(this)
            connection.unregisterStream(localId)
            closePipes()
        }
    }

    internal fun markClosed() {
        if (closedFlag.compareAndSet(false, true)) {
            connection.unregisterStream(localId)
            closePipes()
        }
        readyLatch.countDown()
    }

    private fun closePipes() {
        try {
            pipeOut.close()
        } catch (_: IOException) {
        }
        try {
            pipeIn.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val WRITE_CHUNK = 4096
        private const val INPUT_BUFFER_SIZE = 256 * 1024
        private const val OKAY_TIMEOUT_S = 5L
    }
}
