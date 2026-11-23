package app.scrcpy.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scrcpy.R
import app.scrcpy.scrcpy.ScrcpyOptions

sealed interface Screen {
    data object Home : Screen
    data object Wireless : Screen
    data object Usb : Screen
}

class MainActivity : ComponentActivity() {

    private val screenState = mutableStateOf<Screen>(Screen.Home)
    private var pendingUsbDevice: UsbDevice? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                pendingUsbDevice = null
                startActivity(StreamActivity.intentForUsb(this@MainActivity, device))
            } else if (device != null) {
                pendingUsbDevice = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }
        handleUsbAttachIntent(intent)
        setContent {
            ScrcpyTheme {
                val screen by screenState
                when (screen) {
                    Screen.Home -> HomeScreen(
                        onOpenWireless = { screenState.value = Screen.Wireless },
                        onOpenUsb = { screenState.value = Screen.Usb },
                        onQuickConnect = { target ->
                            startActivity(
                                StreamActivity.intentForWifi(this, target.host, target.port, target.tls)
                            )
                        }
                    )

                    Screen.Wireless -> {
                        BackHandler { screenState.value = Screen.Home }
                        WirelessScreen(
                            onBack = { screenState.value = Screen.Home },
                            onConnect = { host, port, tls ->
                                startActivity(StreamActivity.intentForWifi(this, host, port, tls))
                            }
                        )
                    }

                    Screen.Usb -> {
                        BackHandler { screenState.value = Screen.Home }
                        UsbScreen(
                            onBack = { screenState.value = Screen.Home },
                            onConnect = { device -> connectUsb(device) }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbAttachIntent(intent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun handleUsbAttachIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            screenState.value = Screen.Usb
        }
    }

    private fun connectUsb(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            startActivity(StreamActivity.intentForUsb(this, device))
        } else {
            pendingUsbDevice = device
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "app.scrcpy.USB_PERMISSION"
    }
}

// --------------------------------------------------------------------- Home

@Composable
private fun HomeScreen(
    onOpenWireless: () -> Unit,
    onOpenUsb: () -> Unit,
    onQuickConnect: (SavedTarget) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var options by remember { mutableStateOf(ScrcpyOptions.load(context)) }
    var targets by remember { mutableStateOf(TargetsStore.load(context)) }

    fun update(newOptions: ScrcpyOptions) {
        options = newOptions
        newOptions.save(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_round),
                contentDescription = "Scrcpy icon",
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "Scrcpy",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Mirror one Android to another.\nNo cables needed, no cloud, fully offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        // Connect tiles
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Wifi,
                title = "Wireless",
                subtitle = "Wireless debugging over your local Wi-Fi",
                onClick = onOpenWireless
            )
            ConnectTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Usb,
                title = "USB / OTG",
                subtitle = "USB debugging over an OTG cable",
                onClick = onOpenUsb
            )
        }

        Spacer(Modifier.height(22.dp))

        // Stream tuning
        Text(
            "Stream tuning",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Applied to every new session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                TuningLabel("Video bitrate", "${options.bitrateMbps} Mbps")
                Slider(
                    value = options.bitrateMbps.toFloat(),
                    onValueChange = { update(options.copy(bitrateMbps = it.toInt().coerceIn(1, 200))) },
                    valueRange = 1f..200f,
                    steps = 198
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (preset in listOf(8, 24, 48, 100, 200)) {
                        FilterChip(
                            selected = options.bitrateMbps == preset,
                            onClick = { update(options.copy(bitrateMbps = preset)) },
                            label = { Text("$preset") }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                TuningLabel("Frame rate cap", "${options.maxFps} fps")
                Slider(
                    value = options.maxFps.toFloat(),
                    onValueChange = { update(options.copy(maxFps = it.toInt().coerceIn(30, 144))) },
                    valueRange = 30f..144f,
                    steps = 113
                )
                Text(
                    "Up to 144 fps — the target's display and encoder set the real ceiling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(14.dp))
                TuningLabel("Resolution cap", if (options.maxSize == 0) "Native" else "${options.maxSize} px")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sizes = listOf(0 to "Native", 1920 to "1920", 1280 to "1280", 854 to "854")
                    for ((value, label) in sizes) {
                        FilterChip(
                            selected = options.maxSize == value,
                            onClick = { update(options.copy(maxSize = value)) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                TuningLabel("Video codec", options.videoCodec.uppercase())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in listOf("h264" to "H.264", "h265" to "H.265", "av1" to "AV1")) {
                        FilterChip(
                            selected = options.videoCodec == value,
                            onClick = { update(options.copy(videoCodec = value)) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                ToggleRow("Audio", options.audio) { update(options.copy(audio = it)) }
                if (options.audio) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((value, label) in listOf(
                            "opus" to "Opus", "aac" to "AAC", "flac" to "FLAC", "raw" to "Raw"
                        )) {
                            FilterChip(
                                selected = options.audioCodec == value,
                                onClick = { update(options.copy(audioCodec = value)) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                ToggleRow("Stay awake on target", options.stayAwake) {
                    update(options.copy(stayAwake = it))
                }
                ToggleRow("Turn target screen off", options.turnScreenOff) {
                    update(options.copy(turnScreenOff = it))
                }
                ToggleRow("Show touches on target", options.showTouches) {
                    update(options.copy(showTouches = it))
                }
            }
        }

        // Saved targets
        if (targets.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(
                "Recent targets",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            for (target in targets) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                target.name.ifEmpty { target.host },
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${target.host}:${target.port} · ${if (target.tls) "Wireless debugging" else "TCP/IP"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            TargetsStore.delete(context, target)
                            targets = TargetsStore.load(context)
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Forget target")
                        }
                        IconButton(onClick = { onQuickConnect(target) }) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Connect",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Everything runs between your two devices on your own network or cable. Nothing leaves them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConnectTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TuningLabel(name: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, fontWeight = FontWeight.Medium)
        Text(value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
