package app.scrcpy.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * A byte pipe to an adbd instance. Implementations: TCP socket (optionally
 * upgraded to TLS), ADB-over-TLS socket, and USB bulk endpoints.
 */
interface AdbTransport {
    val input: InputStream
    val output: OutputStream

    /**
     * Upgrades the pipe to TLS in place (the A_STLS flow used on the legacy
     * TCP/IP port). Returns false when the transport cannot be upgraded.
     */
    fun upgradeToTls(sslContext: SSLContext, host: String): Boolean = false

    fun close()
}

/** Plain TCP socket transport; can be wrapped in TLS for A_STLS. */
class SocketTransport(private val socket: Socket) : AdbTransport {
    @Volatile
    private var inStream: InputStream = socket.getInputStream()

    @Volatile
    private var outStream: OutputStream = socket.getOutputStream()

    @Volatile
    private var sslSocket: SSLSocket? = null

    override val input: InputStream
        get() = inStream
    override val output: OutputStream
        get() = outStream

    override fun upgradeToTls(sslContext: SSLContext, host: String): Boolean {
        val ssl = sslContext.socketFactory.createSocket(socket, host, socket.port, true) as SSLSocket
        ssl.useClientMode = true
        ssl.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        ssl.startHandshake()
        sslSocket = ssl
        inStream = ssl.inputStream
        outStream = ssl.outputStream
        return true
    }

    /** Wraps an already-connected socket in TLS immediately (wireless debugging port). */
    fun wrapTlsImmediately(sslContext: SSLContext, host: String) {
        upgradeToTls(sslContext, host)
    }

    override fun close() {
        try {
            sslSocket?.close()
        } catch (_: Throwable) {
        }
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }
}

/**
 * USB transport speaking to the ADB interface (class 0xFF, subclass 0x42,
 * protocol 0x01) over its two bulk endpoints.
 */
class UsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) : AdbTransport {

    @Volatile
    private var closed = false

    override val input: InputStream = object : InputStream() {
        private var buffer = ByteArray(0)
        private var pos = 0
        private var failures = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("USB transport closed")
            if (pos >= buffer.size) {
                val chunk = ByteArray(READ_CHUNK)
                var n = connection.bulkTransfer(epIn, chunk, chunk.size, READ_TIMEOUT_MS)
                while (n < 0) {
                    failures++
                    if (failures >= MAX_READ_FAILURES) {
                        throw IOException("USB bulk read failed $failures times in a row")
                    }
                    n = connection.bulkTransfer(epIn, chunk, chunk.size, READ_TIMEOUT_MS)
                }
                failures = 0
                buffer = if (n == chunk.size) chunk else chunk.copyOf(n)
                pos = 0
            }
            val count = minOf(len, buffer.size - pos)
            System.arraycopy(buffer, pos, b, off, count)
            pos += count
            return count
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("USB transport closed")
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val chunkLen = minOf(remaining, WRITE_CHUNK)
                val chunk = if (offset == 0 && chunkLen == b.size) b
                else b.copyOfRange(offset, offset + chunkLen)
                val n = connection.bulkTransfer(epOut, chunk, chunkLen, WRITE_TIMEOUT_MS)
                if (n < 0) throw IOException("USB bulk write failed")
                offset += n
                remaining -= n
            }
            // Zero-length packet terminates the transfer when the payload is
            // an exact multiple of the endpoint packet size.
            if (len > 0 && len % epOut.maxPacketSize == 0) {
                connection.bulkTransfer(epOut, ByteArray(0), 0, WRITE_TIMEOUT_MS)
            }
        }

        override fun flush() {}
    }

    override fun close() {
        closed = true
        try {
            connection.releaseInterface(iface)
        } catch (_: Throwable) {
        }
        try {
            connection.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val READ_CHUNK = 16384
        private const val WRITE_CHUNK = 16384
        private const val READ_TIMEOUT_MS = 5000
        private const val WRITE_TIMEOUT_MS = 5000
        private const val MAX_READ_FAILURES = 3

        /** The ADB interface of [device], or null when it has none. */
        fun findAdbInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 255 &&
                    iface.interfaceSubclass == 66 &&
                    iface.interfaceProtocol == 1
                ) {
                    return iface
                }
            }
            return null
        }

        fun isAdbDevice(device: UsbDevice): Boolean = findAdbInterface(device) != null

        @Throws(IOException::class)
        fun open(usbManager: UsbManager, device: UsbDevice): UsbTransport {
            val iface = findAdbInterface(device)
                ?: throw IOException("Device has no ADB interface")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) {
                throw IOException("ADB interface is missing a bulk endpoint")
            }
            val connection = usbManager.openDevice(device)
                ?: throw IOException("Could not open USB device (permission missing?)")
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw IOException("Could not claim the ADB interface")
            }
            connection.controlTransfer(0x21, 0x22, 0, 0, null, 0, 1000) // SET_CONTROL_LINE_STATE, best effort
            return UsbTransport(connection, iface, epIn, epOut)
        }
    }
}
