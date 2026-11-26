package dev.agentdesktop.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridge server: newline-delimited JSON over local sockets, one
 * sequential connection at a time per listener. The primary listener is
 * the abstract-namespace local socket; a second listener on TCP
 * loopback (127.0.0.1) serves the same protocol for clients that
 * SELinux keeps from connecting to another app's abstract socket.
 * Both listeners share one connection handler.
 *
 * The first request on a connection must be `hello`; when a token file
 * exists in the app's private storage its `token` must match, otherwise
 * the connection is rejected. Methods: `ping` (status), `get_tree`,
 * `act`, `launch_app`, `list_apps`, `screenshot`. Actions are performed
 * only after the request's capture proof re-matches exactly one live
 * node, in the same request.
 */
object BridgeServer {

    const val SOCKET_NAME = "agent-desktop-bridge"
    const val TCP_PORT = 39271
    private const val TCP_HOST = "127.0.0.1"
    private const val TOKEN_FILE = "bridge-token.txt"

    @Volatile
    private var running = false
    private var serverSocket: LocalServerSocket? = null
    private var worker: Thread? = null
    private var tcpServerSocket: ServerSocket? = null
    private var tcpWorker: Thread? = null
    private var appContext: Context? = null

    @Volatile
    private var service: AgentAccessibilityService? = null

    private val instanceLock = Object()
    private var serviceSession = ""
    private var epochCounter = 0L
    private val packageEpochs = mutableMapOf<String, Long>()

    fun start(accessibilityService: AgentAccessibilityService) {
        service = accessibilityService
        appContext = accessibilityService.applicationContext
        synchronized(instanceLock) {
            serviceSession = SystemClock.elapsedRealtime().toString()
            epochCounter = 0L
            packageEpochs.clear()
        }
        if (running) return
        running = true
        worker = Thread({ acceptLoop() }, "agent-desktop-bridge").apply { isDaemon = true }
        worker?.start()
        tcpWorker = Thread({ tcpAcceptLoop() }, "agent-desktop-bridge-tcp").apply { isDaemon = true }
        tcpWorker?.start()
    }

    fun stop() {
        running = false
        service = null
        synchronized(instanceLock) {
            packageEpochs.clear()
        }
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
        }
        serverSocket = null
        worker = null
        try {
            tcpServerSocket?.close()
        } catch (ignored: Exception) {
        }
        tcpServerSocket = null
        tcpWorker = null
    }

    private class BridgeFailure(val code: String, message: String) : Exception(message)

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

    private fun tcpAcceptLoop() {
        try {
            val server = ServerSocket(TCP_PORT, 50, InetAddress.getByName(TCP_HOST))
            tcpServerSocket = server
            while (running) {
                val socket = try {
                    server.accept()
                } catch (error: Exception) {
                    break
                }
                handleConnection(socket)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun handleConnection(socket: LocalSocket) {
        try {
            handleStreams(socket.inputStream, socket.outputStream)
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            handleStreams(socket.inputStream, socket.outputStream)
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun handleStreams(input: InputStream, output: OutputStream) {
        try {
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val writer = OutputStreamWriter(output, Charsets.UTF_8)
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
                writeLine(writer, dispatch(id, method, request.optJSONObject("params") ?: JSONObject()))
            }
        } catch (ignored: Exception) {
        }
    }

    private fun dispatch(id: Long, method: String, params: JSONObject): String {
        return try {
            when (method) {
                "ping" -> okResponse(id, statusJson())
                "get_tree" -> okResponse(id, getTreeResult())
                "act" -> okResponse(id, actResult(params))
                "launch_app" -> okResponse(id, launchResult(params))
                "list_apps" -> okResponse(id, listAppsResult())
                "screenshot" -> okResponse(id, screenshotResult())
                else -> errorResponse(id, "not_implemented", "method $method is not implemented")
            }
        } catch (failure: BridgeFailure) {
            errorResponse(id, failure.code, failure.message ?: failure.code)
        } catch (error: Exception) {
            errorResponse(id, "internal", error.message ?: "bridge call failed")
        }
    }

    private fun requireService(): AgentAccessibilityService {
        return service ?: throw BridgeFailure(
            "service_unavailable",
            "the accessibility service is not connected"
        )
    }

    private fun getTreeResult(): JSONObject {
        val active = requireService()
        val root = active.rootInActiveWindow ?: throw BridgeFailure(
            "no_active_window",
            "no active window is available"
        )
        try {
            val result = BridgeTree.serialize(root)
            result.put(
                "process_instance",
                processInstance(active, root.packageName?.toString() ?: "")
            )
            return result
        } finally {
            root.recycle()
        }
    }

    /**
     * The process-instance token for one package: "<service session>:<epoch>".
     *
     * Android gives an accessibility service no way to read another app's
     * pid or start time, so this token is the bridge's own observation of
     * app continuity, not an OS process identity. The session part is the
     * bridge service's start time on the monotonic clock, so every token
     * rotates when the service restarts. The epoch part is minted for a
     * package the first time it is observed and kept while the package
     * owns at least one window in the service's window list; when the
     * package leaves the window list entirely (its process gone, its
     * windows destroyed) the epoch is dropped, and a later return mints a
     * fresh one. Focus switches between apps do not rotate the token,
     * because a backgrounded app keeps its windows. Presence is sampled
     * at observation time: a kill and relaunch that both happen between
     * two observations, with the package never observed absent, keeps the
     * old token.
     */
    private fun processInstance(
        active: AgentAccessibilityService,
        activePackage: String
    ): String {
        val present = mutableSetOf<String>()
        if (activePackage.isNotEmpty()) {
            present.add(activePackage)
        }
        try {
            for (window in active.windows) {
                val windowRoot = try {
                    window.root
                } catch (error: Exception) {
                    null
                } ?: continue
                try {
                    val pkg = windowRoot.packageName?.toString() ?: ""
                    if (pkg.isNotEmpty()) {
                        present.add(pkg)
                    }
                } finally {
                    windowRoot.recycle()
                }
            }
        } catch (error: Exception) {
            // Presence falls back to the active package alone.
        }
        synchronized(instanceLock) {
            val iterator = packageEpochs.keys.iterator()
            while (iterator.hasNext()) {
                if (iterator.next() !in present) {
                    iterator.remove()
                }
            }
            val epoch = packageEpochs.getOrPut(activePackage) { ++epochCounter }
            return "$serviceSession:$epoch"
        }
    }

    private fun actResult(params: JSONObject): JSONObject {
        val active = requireService()
        val proofJson = params.optJSONObject("proof")
            ?: throw BridgeFailure("invalid_args", "act requires a proof object")
        val proof = BridgeTree.Proof(
            role = proofJson.optString("role", ""),
            className = proofJson.optString("class", ""),
            viewId = if (proofJson.isNull("view_id")) null else proofJson.optString("view_id"),
            name = if (proofJson.isNull("name")) null else proofJson.optString("name"),
            description = if (proofJson.isNull("description")) null else proofJson.optString("description")
        )
        val action = params.optString("action", "")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val root = active.rootInActiveWindow ?: throw BridgeFailure(
            "no_active_window",
            "no active window is available"
        )
        try {
            val matches = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            BridgeTree.collectMatches(root, proof, matches, true)
            if (matches.size != 1) {
                for (match in matches) {
                    if (match !== root) {
                        match.recycle()
                    }
                }
                return JSONObject()
                    .put("matched", matches.size)
                    .put("performed", false)
            }
            val node = matches[0]
            val performed = if (action == "scroll_into_view") {
                BridgeTree.scrollIntoView(active, node, proof)
            } else {
                BridgeTree.perform(active, node, action, arguments)
            }
            if (node !== root) {
                node.recycle()
            }
            return JSONObject()
                .put("matched", 1)
                .put("performed", performed)
        } finally {
            root.recycle()
        }
    }

    private fun launchResult(params: JSONObject): JSONObject {
        val packageName = params.optString("package", "")
        if (packageName.isEmpty()) {
            throw BridgeFailure("invalid_args", "launch_app requires a package name")
        }
        val context = appContext ?: throw BridgeFailure(
            "service_unavailable",
            "the bridge has no application context"
        )
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw BridgeFailure("app_not_found", "no launchable activity for $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject()
            .put("launched", true)
            .put("package", packageName)
    }

    private fun listAppsResult(): JSONObject {
        val context = appContext ?: throw BridgeFailure(
            "service_unavailable",
            "the bridge has no application context"
        )
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val apps = JSONArray()
        val seen = mutableSetOf<String>()
        val entries = resolved
            .map {
                val pkg = it.activityInfo.packageName
                val label = it.loadLabel(packageManager).toString()
                pkg to label
            }
            .filter { seen.add(it.first) }
            .sortedBy { it.second.lowercase() }
        for ((pkg, label) in entries) {
            apps.put(JSONObject().put("package", pkg).put("label", label))
        }
        return JSONObject().put("apps", apps)
    }

    private fun screenshotResult(): JSONObject {
        val active = requireService()
        val latch = CountDownLatch(1)
        var png: ByteArray? = null
        var width = 0
        var height = 0
        var failure: String? = null
        active.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            active.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer,
                            result.colorSpace
                        )
                        if (wrapped == null) {
                            failure = "screenshot hardware buffer could not be wrapped"
                        } else {
                            val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped.recycle()
                            if (copy == null) {
                                failure = "screenshot bitmap could not be copied"
                            } else {
                                val stream = ByteArrayOutputStream()
                                copy.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                png = stream.toByteArray()
                                width = copy.width
                                height = copy.height
                                copy.recycle()
                            }
                        }
                    } catch (error: Exception) {
                        failure = error.message ?: "screenshot encoding failed"
                    } finally {
                        result.hardwareBuffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    failure = "takeScreenshot failed with error $errorCode"
                    latch.countDown()
                }
            }
        )
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw BridgeFailure("timeout", "takeScreenshot did not answer in time")
        }
        val bytes = png ?: throw BridgeFailure(
            "screenshot_failed",
            failure ?: "screenshot failed"
        )
        return JSONObject()
            .put("png_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("width", width)
            .put("height", height)
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
