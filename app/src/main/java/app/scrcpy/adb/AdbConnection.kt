package app.scrcpy.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A live ADB session with one device, over any [AdbTransport].
 *
 * Threading: a single daemon reader thread dispatches incoming packets to
 * streams; all writes are serialised through one lock. The handshake
 * (A_CNXN / A_AUTH / A_STLS) is handled inside the reader loop, so callers
 * simply create a connection through one of the factory methods and wait
 * for it to become ready.
 */
class AdbConnection private constructor(
    private val transport: AdbTransport,
    private val keys: android.content.Context,
    private val tlsUpgradeHost: String?,
    private val sslContextProvider: (() -> javax.net.ssl.SSLContext)?
) : Closeable {

    private val keyPair = AdbKeys.getOrCreate(keys)
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextLocalId = AtomicInteger(1)
    private val writeLock = Any()
    private val connectedLatch = CountDownLatch(1)

    @Volatile
    private var closed = false

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    var deviceBanner: String = ""
        private set

    private var sentSignature = false
    private var sentPublicKey = false

    val isConnected: Boolean
        get() = connectedLatch.count == 0L && !closed

    // ------------------------------------------------------------------ API

    /**
     * Opens a stream to [service] (e.g. `shell:...`, `sync:`,
     * `localabstract:...`) and waits for the device to accept it.
     */
    @Throws(IOException::class)
    fun open(service: String, timeoutMs: Long = OPEN_TIMEOUT_MS): AdbStream {
        if (!isConnected) throw IOException("ADB connection is not established")
        val stream = AdbStream(this, nextLocalId.getAndIncrement(), service)
        streams[stream.localId] = stream
        val payload = service.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        send(AdbProtocol.A_OPEN, stream.localId, 0, payload)
        if (!stream.awaitReady(timeoutMs)) {
            streams.remove(stream.localId)
            throw IOException("Timed out opening ADB stream '$service'")
        }
        if (stream.isClosed) {
            throw IOException("Device refused ADB stream '$service'")
        }
        return stream
    }

    /** Convenience: run one shell command to completion and collect stdout. */
    @Throws(IOException::class)
    fun shell(command: String): String {
        val stream = open("shell:$command")
        val out = StringBuilder()
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = stream.input.read(buf)
                if (n < 0) break
                out.append(String(buf, 0, n, Charsets.UTF_8))
            }
        } finally {
            stream.close()
        }
        return out.toString()
    }

    override fun close() {
        if (closed) return
        closed = true
        for (stream in streams.values) stream.markClosed()
        streams.clear()
        try {
            transport.close()
        } catch (_: Throwable) {
        }
        connectedLatch.countDown()
    }

    // ------------------------------------------------------- stream plumbing

    internal fun sendWrite(stream: AdbStream, chunk: ByteArray) {
        send(AdbProtocol.A_WRTE, stream.localId, stream.remoteId, chunk)
    }

    internal fun sendOkay(stream: AdbStream) {
        send(AdbProtocol.A_OKAY, stream.localId, stream.remoteId, ByteArray(0))
    }

    internal fun sendClose(stream: AdbStream) {
        if (!closed) {
            try {
                send(AdbProtocol.A_CLSE, stream.localId, stream.remoteId, ByteArray(0))
            } catch (_: Throwable) {
            }
        }
    }

    internal fun unregisterStream(localId: Int) {
        streams.remove(localId)
    }

    private fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        if (closed) throw IOException("ADB connection closed")
        synchronized(writeLock) {
            writeAdbMessage(transport.output, command, arg0, arg1, payload)
        }
    }

    // ------------------------------------------------------------- handshake

    private fun beginHandshake() {
        val reader = Thread({ readerLoop() }, "adb-reader")
        reader.isDaemon = true
        reader.start()
        send(
            AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.ADB_MAX_DATA,
            AdbProtocol.CNXN_PAYLOAD.toByteArray(Charsets.UTF_8)
        )
    }

    private fun readerLoop() {
        try {
            while (!closed) {
                val msg = readAdbMessage(transport.input)
                when (msg.command) {
                    AdbProtocol.A_CNXN -> {
                        deviceBanner = String(msg.payload, Charsets.UTF_8)
                        connectedLatch.countDown()
                    }

                    AdbProtocol.A_STLS -> {
                        // Legacy TCP/IP endpoint asking for a TLS upgrade.
                        val provider = sslContextProvider
                            ?: throw IOException("Device requested STLS but no TLS context is available")
                        if (!transport.upgradeToTls(provider(), tlsUpgradeHost ?: "")) {
                            throw IOException("Transport cannot be upgraded to TLS")
                        }
                        send(
                            AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.ADB_MAX_DATA,
                            AdbProtocol.CNXN_PAYLOAD.toByteArray(Charsets.UTF_8)
                        )
                    }

                    AdbProtocol.A_AUTH -> {
                        if (msg.arg0 == AdbProtocol.AUTH_TOKEN) {
                            if (!sentSignature) {
                                sentSignature = true
                                val signature = AdbKeys.signToken(keyPair.private, msg.payload)
                                send(AdbProtocol.A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, signature)
                            } else if (!sentPublicKey) {
                                sentPublicKey = true
                                Log.i(TAG, "Sending public key; tap Allow on the device")
                                val pub = AdbKeys.adbPublicKeyLine(keyPair.public as RSAPublicKey)
                                send(
                                    AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0,
                                    pub.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                                )
                            }
                            // Further tokens are ignored: the device will
                            // either accept the key (A_CNXN) or drop us.
                        }
                    }

                    AdbProtocol.A_OKAY -> streams[msg.arg1]?.onOkay(msg.arg0)
                    AdbProtocol.A_WRTE -> streams[msg.arg1]?.onData(msg.payload)
                    AdbProtocol.A_CLSE -> streams[msg.arg1]?.onCloseFromDevice()
                    else -> Log.d(TAG, "Ignoring ADB packet cmd=0x${msg.command.toString(16)}")
                }
            }
        } catch (t: Throwable) {
            if (!closed) {
                failure = t
                Log.w(TAG, "ADB reader stopped: ${t.message}")
            }
        } finally {
            connectedLatch.countDown()
            for (stream in streams.values) stream.markClosed()
        }
    }

    private fun awaitConnected() {
        if (!connectedLatch.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)) {
            close()
            throw IOException("ADB authentication timed out (pairing or Allow prompt needed?)")
        }
        failure?.let { throw IOException("ADB connection failed: ${it.message}", it) }
        if (!isConnected) throw IOException("ADB connection closed during handshake")
    }

    companion object {
        private const val TAG = "ScrcpyAdb"
        private const val CONNECT_TIMEOUT_S = 30L
        private const val OPEN_TIMEOUT_MS = 5000L
        private const val SOCKET_TIMEOUT_MS = 15_000

        /**
         * Legacy ADB-over-TCP (the `adb tcpip 5555` mode): plaintext socket,
         * classic token authentication, with an A_STLS → TLS upgrade when the
         * device asks for it.
         */
        @Throws(IOException::class)
        fun connectTcp(context: Context, host: String, port: Int = 5555): AdbConnection {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            val transport = SocketTransport(socket)
            val conn = AdbConnection(
                transport, context, host,
                sslContextProvider = { AdbKeys.sslContext(context) }
            )
            conn.beginHandshake()
            conn.awaitConnected()
            socket.soTimeout = 0
            return conn
        }

        /**
         * Wireless debugging endpoint: TLS from the first byte, using the
         * ADB certificate. The key must already be trusted by the device
         * (via [PairingClient] or a previous USB Allow).
         */
        @Throws(IOException::class)
        fun connectTls(context: Context, host: String, port: Int): AdbConnection {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            val transport = SocketTransport(socket)
            transport.wrapTlsImmediately(AdbKeys.sslContext(context), host)
            val conn = AdbConnection(transport, context, null, null)
            conn.beginHandshake()
            conn.awaitConnected()
            socket.soTimeout = 0
            return conn
        }

        /** USB debugging over the device's ADB interface. */
        @Throws(IOException::class)
        fun connectUsb(context: Context, device: UsbDevice): AdbConnection {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val transport = UsbTransport.open(usbManager, device)
            val conn = AdbConnection(transport, context, null, null)
            conn.beginHandshake()
            conn.awaitConnected()
            return conn
        }
    }
}
