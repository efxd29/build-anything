package app.scrcpy.adb

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The device's ADB identity: one RSA-2048 key pair, created on first use and
 * kept in the app's private SharedPreferences.
 *
 * The same key is used for three things, exactly like the desktop adb key:
 *  - signing A_AUTH tokens (classic ADB authentication),
 *  - the public-key blob the device stores when the user taps "Allow",
 *  - the self-signed X.509 certificate presented in ADB-over-TLS
 *    (wireless debugging) connections and during pairing.
 */
object AdbKeys {
    private const val PREFS = "adb_identity"
    private const val KEY_PRIV = "priv"
    private const val KEY_NAME = "scrcpy@android"

    @Volatile
    private var cached: KeyPair? = null

    @Volatile
    private var cachedCert: X509Certificate? = null

    init {
        if (Security.getProvider("SCRC") == null) {
            // Bundled BouncyCastle, registered under a side name so it never
            // clashes with the platform provider.
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Synchronized
    fun getOrCreate(context: Context): KeyPair {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_PRIV, null)
        if (saved != null) {
            try {
                val der = Base64.decode(saved, Base64.NO_WRAP)
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
                val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, RSA_EXPONENT))
                return KeyPair(pub, priv).also { cached = it }
            } catch (t: Throwable) {
                // Corrupt entry: fall through and generate a fresh identity.
            }
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIV, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .apply()
        cached = pair
        return pair
    }

    private val RSA_EXPONENT: BigInteger = BigInteger.valueOf(65537)

    /**
     * Encodes an RSA public key into Android's binary public-key format
     * (the `struct android_pubkey` layout), then Base64 + key name, i.e. one
     * line of an `adb_keys` / `authorized_keys` file:
     *
     *   uint32 modulus_size_words (64)
     *   uint32 n0inv              (-n^-1 mod 2^32)
     *   uint8  modulus[256]       little-endian word order
     *   uint8  rr[256]            (2^4096 mod n), same encoding
     *   uint32 exponent
     */
    fun adbPublicKeyLine(publicKey: RSAPublicKey): String {
        val n = publicKey.modulus
        val words = 64
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(two32).modInverse(two32).negate().mod(two32)
        val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)

        val blob = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        blob.putInt(words)
        blob.putInt(n0inv.toInt())
        putLittleEndianWords(blob, n, words)
        putLittleEndianWords(blob, rr, words)
        blob.putInt(publicKey.publicExponent.toInt())
        val encoded = Base64.encodeToString(blob.array(), Base64.NO_WRAP)
        return "$encoded $KEY_NAME"
    }

    private fun putLittleEndianWords(buffer: java.nio.ByteBuffer, value: BigInteger, words: Int) {
        // BigInteger.toByteArray() is big-endian; ADB stores the modulus as
        // little-endian 32-bit words, least significant word first.
        val raw = value.toByteArray()
        for (i in 0 until words) {
            var word = 0
            for (j in 0 until 4) {
                val idx = raw.size - 1 - (i * 4 + j)
                val b = if (idx >= 0) raw[idx].toInt() and 0xFF else 0
                word = word or (b shl (8 * j))
            }
            buffer.putInt(word)
        }
    }

    /**
     * Signs an A_AUTH token. ADB expects a PKCS#1 v1.5 signature over the
     * SHA-1 DigestInfo of the token (the token itself is treated as opaque
     * bytes whose SHA-1 is signed), built here manually so the result is
     * byte-identical to what the adb host produces.
     */
    fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(token)
        // DigestInfo prefix for SHA-1: SEQUENCE(SEQUENCE(OID sha1, NULL), OCTET STRING)
        val digestInfoPrefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x10
        )
        val keySize = 256 // 2048-bit key
        val t = digestInfoPrefix + sha1
        val block = ByteArray(keySize)
        block[0] = 0x00
        block[1] = 0x01
        val psLen = keySize - t.size - 3
        for (i in 0 until psLen) block[2 + i] = 0xFF.toByte()
        block[2 + psLen] = 0x00
        System.arraycopy(t, 0, block, 3 + psLen, t.size)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(block)
    }

    /** Self-signed certificate wrapping the ADB key, for TLS connections. */
    @Synchronized
    fun certificate(context: Context): X509Certificate {
        cachedCert?.let { return it }
        val pair = getOrCreate(context)
        val name = X500Name("CN=scrcpy")
        val now = System.currentTimeMillis()
        val serial = BigInteger(64, SecureRandom())
        val builder = JcaX509v3CertificateBuilder(
            name,
            serial,
            Date(now - 24L * 3600 * 1000),
            Date(now + 20L * 365 * 24 * 3600 * 1000),
            name,
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val holder = builder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        cachedCert = cert
        return cert
    }

    /**
     * TLS client context for ADB-over-TLS: presents our certificate and
     * accepts the device's self-signed certificate (trust is established by
     * the pairing step / the on-device Allow prompt, not by PKI).
     */
    fun sslContext(context: Context): SSLContext {
        val pair = getOrCreate(context)
        val cert = certificate(context)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setKeyEntry("adb", pair.private, CharArray(0), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, trustAll, SecureRandom())
        return ctx
    }

    /** Parses a certificate back from DER (used by tests/tools if needed). */
    fun parseCertificate(der: ByteArray): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }
}
