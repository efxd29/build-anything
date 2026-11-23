package app.scrcpy.adb

import android.content.Context
import android.util.Log
import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * Client for the Android 11+ wireless-debugging pairing protocol
 * (AOSP `packages/modules/adb/pairing_connection` + `pairing_auth`):
 *
 *   1. TLS 1.3 to the pairing port, presenting our ADB certificate.
 *   2. The SPAKE2 password is the pairing code concatenated with 64 bytes
 *      of TLS keying material exported with the label "adb-label\0", which
 *      binds the PAKE to this TLS connection.
 *   3. SPAKE2 messages are exchanged in 6-byte-header packets
 *      (version=1, type, big-endian size); type 0 = SPAKE2, type 1 = peer info.
 *   4. The session key is HKDF-SHA256(spake key, info =
 *      "adb pairing_auth aes-128-gcm key", 16 bytes); peer info blobs
 *      (8192 bytes) are exchanged AES-128-GCM-encrypted with a
 *      little-endian counter nonce.
 *   5. Our peer info carries the ADB public key; once the device decrypts
 *      and stores it, our key is trusted for wireless connections.
 */
object PairingClient {
    private const val TAG = "ScrcpyPairing"

    private const val PACKET_VERSION: Byte = 1
    private const val TYPE_SPAKE2_MSG: Byte = 0
    private const val TYPE_PEER_INFO: Byte = 1
    private const val PEER_INFO_SIZE = 8192
    private const val PEER_INFO_TYPE_RSA_KEY: Byte = 0

    private const val EXPORT_LABEL = "adb-label\u0000"
    private const val EXPORT_SIZE = 64
    private val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
    private val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)

    class PairingException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Pairs with the device at [host]:[pairPort] using the 6-digit [code]
     * shown on the device. Returns the device GUID from its peer info.
     * Blocking; call from a worker thread.
     */
    @Throws(IOException::class)
    fun pair(context: Context, host: String, pairPort: Int, code: String): String {
        val keyPair = AdbKeys.getOrCreate(context)
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = 30_000
        socket.connect(InetSocketAddress(host, pairPort), 15_000)

        val sslContext = AdbKeys.sslContext(context)
        val ssl = sslContext.socketFactory.createSocket(socket, host, pairPort, true) as SSLSocket
        try {
            ssl.useClientMode = true
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            ssl.startHandshake()

            val exported = exportKeyingMaterial(ssl)
            val password = code.toByteArray(Charsets.UTF_8) + exported

            val spake = Spake2Context(Spake2Role.Alice, CLIENT_NAME, SERVER_NAME)
            val ourMsg = spake.generateMessage(password)
            val input = DataInputStream(ssl.inputStream)
            val output = ssl.outputStream

            writePacket(output, TYPE_SPAKE2_MSG, ourMsg)
            val theirPacket = readPacket(input)
            if (theirPacket.first != TYPE_SPAKE2_MSG) {
                throw PairingException("Unexpected packet type ${theirPacket.first} during SPAKE2 exchange")
            }
            val keyMaterial = spake.processMessage(theirPacket.second)
                ?: throw PairingException("SPAKE2 failed — wrong pairing code?")
            val aesKey = hkdfSha256(keyMaterial, null, HKDF_INFO, 16)

            val cipher = AesGcmCounter(aesKey)
            val ourPeerInfo = ByteArray(PEER_INFO_SIZE)
            ourPeerInfo[0] = PEER_INFO_TYPE_RSA_KEY
            val keyLine = AdbKeys.adbPublicKeyLine(keyPair.public as RSAPublicKey)
                .toByteArray(Charsets.UTF_8)
            System.arraycopy(keyLine, 0, ourPeerInfo, 1, minOf(keyLine.size, PEER_INFO_SIZE - 1))
            writePacket(output, TYPE_PEER_INFO, cipher.encrypt(ourPeerInfo))

            val responsePacket = readPacket(input)
            if (responsePacket.first != TYPE_PEER_INFO) {
                throw PairingException("Unexpected packet type ${responsePacket.first} during peer info exchange")
            }
            val theirPeerInfo = try {
                cipher.decrypt(responsePacket.second)
            } catch (t: Throwable) {
                throw PairingException("Could not decrypt device peer info — wrong pairing code?", t)
            }
            val guidBytes = theirPeerInfo.drop(1).takeWhile { it != 0.toByte() }.toByteArray()
            val guid = String(guidBytes, Charsets.UTF_8)
            Log.i(TAG, "Pairing succeeded; device GUID: $guid")
            return guid
        } finally {
            try {
                ssl.close()
            } catch (_: Throwable) {
            }
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun exportKeyingMaterial(socket: SSLSocket): ByteArray {
        // Conscrypt's static exportKeyingMaterial(SSLSocket, String, byte[], int),
        // reached reflectively because it is not part of the public Android API.
        try {
            val conscrypt = Class.forName("org.conscrypt.Conscrypt")
            val method = conscrypt.getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            return method.invoke(null, socket, EXPORT_LABEL, null, EXPORT_SIZE) as ByteArray
        } catch (t: Throwable) {
            throw PairingException("TLS keying material export is unavailable on this device", t)
        }
    }

    private fun writePacket(out: OutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        header.put(PACKET_VERSION)
        header.put(type)
        header.putInt(payload.size)
        out.write(header.array())
        out.write(payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Byte, ByteArray> {
        val header = ByteArray(6)
        input.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get(0)
        if (version != PACKET_VERSION) {
            throw PairingException("Unsupported pairing packet version $version")
        }
        val type = buffer.get(1)
        val size = buffer.getInt(2)
        if (size < 0 || size > PEER_INFO_SIZE * 2) {
            throw PairingException("Pairing payload too large: $size")
        }
        val payload = ByteArray(size)
        input.readFully(payload)
        return type to payload
    }

    /** RFC 5869 HKDF with HMAC-SHA-256. */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val actualSalt = salt ?: ByteArray(32)
        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = prkMac.doFinal(ikm)
        val out = ByteArray(length)
        var produced = 0
        var counter = 1
        var previous = ByteArray(0)
        val expandMac = Mac.getInstance("HmacSHA256")
        while (produced < length) {
            expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
            expandMac.update(previous)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            previous = expandMac.doFinal()
            val take = minOf(previous.size, length - produced)
            System.arraycopy(previous, 0, out, produced, take)
            produced += take
            counter++
        }
        return out
    }

    /** AES-128-GCM with the protocol's counter nonce (LE u64 in a 12-byte IV). */
    private class AesGcmCounter(key: ByteArray) {
        private val keySpec = SecretKeySpec(key, "AES")
        private var encCounter = 0L
        private var decCounter = 0L

        fun encrypt(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(encCounter++)))
            return cipher.doFinal(plain)
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(decCounter++)))
            return cipher.doFinal(ciphertext)
        }

        private fun nonce(counter: Long): ByteArray {
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(counter).array()
        }
    }
}
