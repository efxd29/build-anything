package dev.agentdesktop.bridge

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Minimal bridge server: newline-delimited JSON over an abstract-namespace
 * local socket, one sequential connection at a time.
 *
 * The first request on a connection must be `hello`; when a token file
 * exists in the app's private storage its `token` must match, otherwise the
 * connection is rejected. `hello` and `ping` both answer with the status
 * object the Rust client's permission report consumes. Every other method
 * currently answers `not_implemented` and lands in later phases.
 */
object BridgeServer {

    const val SOCKET_NAME = "agent-desktop-bridge"
    private const val TOKEN_FILE = "bridge-token.txt"

    @Volatile
    private var running = false
    private var serverSocket: LocalServerSocket? = null
    private var worker: Thread? = null
    private var appContext: Context? = null

    fun start(service: AgentAccessibilityService) {
        if (running) return
        appContext = service.applicationContext
        running = true
        worker = Thread({ acceptLoop() }, "agent-desktop-bridge").apply { isDaemon = true }
        worker?.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
        }
        serverSocket = null
        worker = null
    }

    private fun acceptLoop() {
        try {
            val server = LocalServerSocket(SOCKET_NAME)
            serverSocket = server
            while (running) {
                val socket = try {
                    server.accept()
                } catch (error: Exception) {
                    break
                }
                handleConnection(socket)
            }
        } catch (error: Exception) {
            running = false
        }
    }

    private fun handleConnection(socket: LocalSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
            var greeted = false
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val request = try {
                    JSONObject(line)
                } catch (error: Exception) {
                    writeLine(writer, errorResponse(0, "invalid_args", "request is not a JSON object"))
                    continue
                }
                val id = request.optLong("id", 0)
                val method = request.optString("method", "")
                if (!greeted) {
                    if (method != "hello") {
                        writeLine(writer, errorResponse(id, "protocol", "first request must be hello"))
                        break
                    }
                    val stored = storedToken()
                    val offered = request.optJSONObject("params")?.optString("token", "") ?: ""
                    if (!stored.isNullOrEmpty() && stored != offered) {
                        writeLine(writer, errorResponse(id, "token_rejected", "hello token does not match"))
                        break
                    }
                    greeted = true
                    writeLine(writer, okResponse(id, statusJson()))
                    continue
                }
                when (method) {
                    "ping" -> writeLine(writer, okResponse(id, statusJson()))
                    else -> writeLine(
                        writer,
                        errorResponse(id, "not_implemented", "method $method is not implemented yet")
                    )
                }
            }
        } catch (ignored: Exception) {
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun statusJson(): JSONObject {
        return JSONObject()
            .put("protocol", 1)
            .put("android_release", Build.VERSION.RELEASE ?: "")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("accessibility", AgentAccessibilityService.instance != null)
            .put("notification_listener", notificationListenerEnabled())
    }

    private fun notificationListenerEnabled(): Boolean {
        val context = appContext ?: return false
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    private fun storedToken(): String? {
        val context = appContext ?: return null
        val file = File(context.filesDir, TOKEN_FILE)
        if (!file.exists()) return null
        return file.readText().trim().ifEmpty { null }
    }

    private fun okResponse(id: Long, result: JSONObject): String {
        return JSONObject()
            .put("id", id)
            .put("ok", true)
            .put("result", result)
            .toString()
    }

    private fun errorResponse(id: Long, code: String, message: String): String {
        return JSONObject()
            .put("id", id)
            .put("ok", false)
            .put(
                "error",
                JSONObject().put("code", code).put("message", message)
            )
            .toString()
    }

    private fun writeLine(writer: OutputStreamWriter, line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }
}
