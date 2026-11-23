package app.scrcpy.scrcpy

import android.content.Context
import android.util.Log
import app.scrcpy.adb.AdbConnection
import app.scrcpy.adb.AdbStream
import app.scrcpy.adb.AdbSync
import java.io.DataInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

/** What the server told us during the video/audio handshake. */
data class DeviceInfo(
    val name: String,
    val videoCodec: Int,
    val audioCodec: Int,
    val width: Int,
    val height: Int
)

/**
 * One running scrcpy session against a device.
 *
 * Lifecycle: the official scrcpy-server (v[SERVER_VERSION], bundled as an
 * asset, originally from the Genymobile/scrcpy project) is pushed to the
 * device if its MD5 differs, started through `app_process` on a shell
 * stream, and then reached through three ADB streams tunnelled to its
 * abstract socket: video, audio and control.
 */
class ScrcpySession private constructor(
    val deviceInfo: DeviceInfo,
    val videoStream: AdbStream,
    val audioStream: AdbStream?,
    val controlStream: AdbStream,
    private val serverStream: AdbStream
) {
    fun close() {
        for (stream in listOfNotNull(videoStream, audioStream, controlStream, serverStream)) {
            try {
                stream.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "ScrcpySession"
        const val SERVER_VERSION = "3.3.4"
        private const val SERVER_ASSET = "scrcpy-server"
        private const val DEVICE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar"
        private const val TUNNEL_RETRIES = 60
        private const val TUNNEL_RETRY_DELAY_MS = 100L

        @Throws(IOException::class)
        fun start(context: Context, connection: AdbConnection, options: ScrcpyOptions): ScrcpySession {
            val serverBytes = context.assets.open(SERVER_ASSET).use { it.readBytes() }
            if (needsPush(connection, serverBytes)) {
                Log.i(TAG, "Pushing scrcpy-server (${serverBytes.size} bytes)")
                AdbSync.push(connection, serverBytes, DEVICE_SERVER_PATH)
            } else {
                Log.i(TAG, "scrcpy-server already up to date on device")
            }

            val scid = "%08x".format(Random.nextInt(0, Int.MAX_VALUE))
            val socketName = "scrcpy_$scid"
            val command = buildServerCommand(scid, options)
            Log.i(TAG, "Starting server: $command")
            val serverStream = connection.open("shell:$command")
            startServerLogThread(serverStream)

            var video: AdbStream? = null
            var audio: AdbStream? = null
            var control: AdbStream? = null
            var lastError: Throwable? = null
            for (attempt in 1..TUNNEL_RETRIES) {
                try {
                    if (video == null) video = connection.open("localabstract:$socketName", 1500)
                    if (options.audio && audio == null) {
                        audio = connection.open("localabstract:$socketName", 1500)
                    }
                    if (control == null) control = connection.open("localabstract:$socketName", 1500)
                    break
                } catch (t: Throwable) {
                    lastError = t
                    Thread.sleep(TUNNEL_RETRY_DELAY_MS)
                }
            }
            val videoStream = video ?: throw IOException("Could not open video tunnel", lastError)
            val controlStream = control ?: throw IOException("Could not open control tunnel", lastError)
            if (options.audio && audio == null) {
                throw IOException("Could not open audio tunnel", lastError)
            }

            // Video handshake: 1 dummy byte, 64-byte device name, codec id,
            // width, height (all big-endian, per the scrcpy protocol).
            val videoIn = DataInputStream(videoStream.input)
            videoIn.readUnsignedByte()
            val nameBytes = ByteArray(64)
            videoIn.readFully(nameBytes)
            val deviceName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
            val videoCodec = videoIn.readInt()
            val width = videoIn.readInt()
            val height = videoIn.readInt()

            var audioCodec = 0
            if (audio != null) {
                audioCodec = DataInputStream(audio.input).readInt()
            }

            val info = DeviceInfo(deviceName, videoCodec, audioCodec, width, height)
            Log.i(TAG, "Session up: $info")
            return ScrcpySession(info, videoStream, audio, controlStream, serverStream)
        }

        private fun buildServerCommand(scid: String, o: ScrcpyOptions): String {
            val sb = StringBuilder()
            sb.append("CLASSPATH=").append(DEVICE_SERVER_PATH)
            sb.append(" app_process / com.genymobile.scrcpy.Server ").append(SERVER_VERSION)
            sb.append(" scid=").append(scid)
            sb.append(" log_level=info")
            sb.append(" video=true control=true tunnel_forward=true")
            sb.append(" audio=").append(o.audio)
            sb.append(" video_codec=").append(o.videoCodec)
            sb.append(" video_bit_rate=").append(o.bitrateBps)
            sb.append(" max_fps=").append(o.maxFps)
            if (o.maxSize > 0) sb.append(" max_size=").append(o.maxSize)
            if (o.audio && o.audioCodec != "opus") sb.append(" audio_codec=").append(o.audioCodec)
            if (o.stayAwake) sb.append(" stay_awake=true")
            if (o.turnScreenOff) sb.append(" turn_screen_off=true")
            if (o.showTouches) sb.append(" show_touches=true")
            return sb.toString()
        }

        private fun needsPush(connection: AdbConnection, serverBytes: ByteArray): Boolean {
            val localMd5 = MessageDigest.getInstance("MD5").digest(serverBytes)
                .joinToString("") { "%02x".format(it) }
            return try {
                val out = connection.shell("md5sum $DEVICE_SERVER_PATH 2>/dev/null")
                val remoteMd5 = out.trim().split(Regex("\\s+")).firstOrNull() ?: ""
                remoteMd5 != localMd5
            } catch (t: Throwable) {
                true
            }
        }

        private fun startServerLogThread(serverStream: AdbStream) {
            val thread = Thread({
                try {
                    val reader = serverStream.input.bufferedReader(Charsets.UTF_8)
                    while (true) {
                        val line = reader.readLine() ?: break
                        Log.d("ScrcpyServer", line)
                    }
                } catch (_: Throwable) {
                    // Stream closed: session is over.
                }
            }, "scrcpy-server-log")
            thread.isDaemon = true
            thread.start()
        }
    }
}
