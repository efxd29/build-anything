package app.scrcpy.ui

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.scrcpy.adb.AdbConnection
import app.scrcpy.scrcpy.AudioPlayer
import app.scrcpy.scrcpy.ControlSender
import app.scrcpy.scrcpy.ScrcpyOptions
import app.scrcpy.scrcpy.ScrcpyProtocol
import app.scrcpy.scrcpy.ScrcpySession
import app.scrcpy.scrcpy.VideoDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The mirroring screen: a fullscreen Surface fed by [VideoDecoder], touch
 * forwarded through [ControlSender], and a small floating toolbar for the
 * Android navigation keys. Everything else is the target's own screen.
 */
class StreamActivity : ComponentActivity() {

    private var connection: AdbConnection? = null
    private var session: ScrcpySession? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer: AudioPlayer? = null
    private var controlSender: ControlSender? = null

    @Volatile
    private var surface: Surface? = null

    private val statusText = mutableStateOf("Connecting…")
    private val errorText = mutableStateOf<String?>(null)
    private val connected = mutableStateOf(false)
    private val statsText = mutableStateOf("")
    private val videoAspect = mutableStateOf(0f)

    private var connectStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val hasUsb = intent.hasExtra(EXTRA_USB_DEVICE)
        val hasWifi = intent.hasExtra(EXTRA_HOST)
        if (!hasUsb && !hasWifi) {
            finish()
            return
        }
        setContent {
            ScrcpyTheme {
                StreamScreen(
                    statusText = statusText.value,
                    errorText = errorText.value,
                    connected = connected.value,
                    statsText = statsText.value,
                    videoAspect = videoAspect.value,
                    onSurfaceReady = { s -> onSurfaceReady(s) },
                    onSurfaceDestroyed = { surface = null },
                    onTouch = { event, w, h -> forwardTouch(event, w, h) },
                    onScroll = { event, w, h ->
                        controlSender?.sendScrollEvent(event, w, h)
                        Unit
                    },
                    onDisconnect = {
                        teardown()
                        finish()
                    },
                    controlSenderProvider = { controlSender }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        videoDecoder?.releaseCodec()
        audioPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        audioPlayer?.resume()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    // ------------------------------------------------------------- lifecycle

    private fun onSurfaceReady(newSurface: Surface) {
        surface = newSurface
        if (connected.value) {
            videoDecoder?.restartCodec(newSurface)
            controlSender?.sendResetVideo()
        } else if (!connectStarted) {
            connectStarted = true
            startConnection()
        }
    }

    private fun startConnection() {
        val options = ScrcpyOptions.load(this)
        lifecycleScope.launch {
            try {
                val conn = withContext(Dispatchers.IO) {
                    statusText.value = "Connecting…"
                    val usbDevice: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(EXTRA_USB_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(EXTRA_USB_DEVICE)
                        }
                    when {
                        usbDevice != null -> AdbConnection.connectUsb(this@StreamActivity, usbDevice)
                        else -> {
                            val host = intent.getStringExtra(EXTRA_HOST)!!
                            val port = intent.getIntExtra(EXTRA_PORT, 5555)
                            val tls = intent.getBooleanExtra(EXTRA_TLS, true)
                            if (tls) AdbConnection.connectTls(this@StreamActivity, host, port)
                            else AdbConnection.connectTcp(this@StreamActivity, host, port)
                        }
                    }
                }
                connection = conn
                statusText.value = "Starting mirror…"
                val sess = withContext(Dispatchers.IO) {
                    ScrcpySession.start(this@StreamActivity, conn, options)
                }
                session = sess
                val info = sess.deviceInfo
                videoAspect.value = if (info.height > 0) {
                    info.width.toFloat() / info.height.toFloat()
                } else 0f

                val sender = ControlSender(sess.controlStream, info.width, info.height)
                controlSender = sender
                val decoder = VideoDecoder(
                    sess.videoStream, info.videoCodec, info.width, info.height, options.maxFps
                ) { fps, kbps ->
                    statsText.value = "$fps fps · ${"%.1f".format(kbps / 1000.0)} Mbps · " +
                        "${info.width}×${info.height}"
                }
                videoDecoder = decoder
                statusText.value = "Streaming ${info.name}"
                connected.value = true
                surface?.let { decoder.start(it) }
                if (sess.audioStream != null) {
                    val player = AudioPlayer(sess.audioStream, info.audioCodec)
                    audioPlayer = player
                    player.start()
                }
            } catch (t: Throwable) {
                errorText.value = t.message ?: t.toString()
            }
        }
    }

    private fun forwardTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        val sender = controlSender ?: return false
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            for (i in 0 until event.pointerCount) {
                sender.sendTouchMove(
                    event.getPointerId(i).toLong(),
                    event.getX(i), event.getY(i),
                    event.getPressure(i),
                    width, height
                )
            }
        } else {
            sender.sendTouchEvent(event, width, height)
        }
        return true
    }

    private fun teardown() {
        try {
            controlSender?.close()
        } catch (_: Throwable) {
        }
        controlSender = null
        try {
            videoDecoder?.stop()
        } catch (_: Throwable) {
        }
        videoDecoder = null
        try {
            audioPlayer?.stop()
        } catch (_: Throwable) {
        }
        audioPlayer = null
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        try {
            connection?.close()
        } catch (_: Throwable) {
        }
        connection = null
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_TLS = "tls"
        private const val EXTRA_USB_DEVICE = "usb_device"

        fun intentForWifi(context: Context, host: String, port: Int, tls: Boolean): Intent {
            return Intent(context, StreamActivity::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_TLS, tls)
        }

        fun intentForUsb(context: Context, device: UsbDevice): Intent {
            return Intent(context, StreamActivity::class.java)
                .putExtra(EXTRA_USB_DEVICE, device)
        }
    }
}

// --------------------------------------------------------------------- UI

@Composable
private fun StreamScreen(
    statusText: String,
    errorText: String?,
    connected: Boolean,
    statsText: String,
    videoAspect: Float,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTouch: (MotionEvent, Int, Int) -> Boolean,
    onScroll: (MotionEvent, Int, Int) -> Unit,
    onDisconnect: () -> Unit,
    controlSenderProvider: () -> ControlSender?
) {
    var toolbarVisible by remember { mutableStateOf(false) }
    LaunchedEffect(toolbarVisible) {
        if (toolbarVisible) {
            delay(4000)
            toolbarVisible = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val surfaceModifier = if (videoAspect > 0f) {
            Modifier.aspectRatio(videoAspect).fillMaxSize()
        } else {
            Modifier.fillMaxSize()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceReady(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder, format: Int, width: Int, height: Int
                            ) {
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurfaceDestroyed()
                            }
                        })
                        setOnTouchListener { view, event ->
                            if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                                onScroll(event, view.width, view.height)
                                true
                            } else {
                                onTouch(event, view.width, view.height)
                            }
                        }
                    }
                },
                modifier = surfaceModifier
            )
        }

        if (!connected) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ScrcpyPalette.Background),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (errorText != null) {
                    Text(
                        "Connection failed",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onDisconnect) { Text("Back") }
                } else {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(14.dp))
                    Text(statusText, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        } else {
            // Stats chip
            if (statsText.isNotEmpty()) {
                MaterialSurface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    Text(
                        statsText,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Toolbar handle + toolbar
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (toolbarVisible) {
                    val sender = controlSenderProvider()
                    MaterialSurface(
                        color = Color.Black.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ToolbarButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") {
                                sender?.sendBackOrScreenOn()
                            }
                            ToolbarButton(Icons.Filled.Home, "Home") {
                                sender?.sendKeyPress(ScrcpyProtocol.KEYCODE_HOME)
                            }
                            ToolbarButton(Icons.Filled.CropSquare, "Recents") {
                                sender?.sendKeyPress(ScrcpyProtocol.KEYCODE_APP_SWITCH)
                            }
                            ToolbarButton(Icons.Filled.Notifications, "Notifications") {
                                sender?.sendExpandNotifications()
                            }
                            ToolbarButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume down") {
                                sender?.sendKeyPress(ScrcpyProtocol.KEYCODE_VOLUME_DOWN)
                            }
                            ToolbarButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume up") {
                                sender?.sendKeyPress(ScrcpyProtocol.KEYCODE_VOLUME_UP)
                            }
                            ToolbarButton(Icons.Filled.ScreenRotation, "Rotate") {
                                sender?.sendRotateDevice()
                            }
                            ToolbarButton(Icons.Filled.PowerSettingsNew, "Power") {
                                sender?.sendKeyPress(ScrcpyProtocol.KEYCODE_POWER)
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = onDisconnect) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Disconnect",
                                    tint = ScrcpyPalette.Danger
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                MaterialSurface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    IconButton(
                        onClick = { toolbarVisible = !toolbarVisible },
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Toggle toolbar",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}
