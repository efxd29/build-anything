package app.scrcpy.ui

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.scrcpy.adb.DiscoveredEndpoint
import app.scrcpy.adb.MdnsDiscovery
import app.scrcpy.adb.PairingClient
import app.scrcpy.adb.UsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun ScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(16.dp))
}

// ------------------------------------------------------------------ Wireless

@Composable
fun WirelessScreen(
    onBack: () -> Unit,
    onConnect: (host: String, port: Int, tls: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var tlsMode by remember { mutableStateOf(true) }

    var pairHost by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var pairStatus by remember { mutableStateOf<String?>(null) }

    val discovered = remember { mutableStateListOf<DiscoveredEndpoint>() }
    DisposableEffect(Unit) {
        val discovery = MdnsDiscovery(context) { endpoint ->
            val index = discovered.indexOfFirst {
                it.kind == endpoint.kind && it.host == endpoint.host && it.port == endpoint.port
            }
            if (index >= 0) discovered[index] = endpoint else discovered.add(endpoint)
        }
        discovery.start()
        onDispose { discovery.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        ScreenHeader(
            "Wireless",
            "Pure device-to-device over your local network — no internet involved.",
            onBack
        )

        // Discovered endpoints
        Text("On this network", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (discovered.isEmpty()) {
            Text(
                "Searching for devices with wireless debugging on…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            for (endpoint in discovered) {
                Card(
                    onClick = {
                        if (endpoint.kind == DiscoveredEndpoint.Kind.CONNECT) {
                            host = endpoint.host
                            connectPort = endpoint.port.toString()
                            tlsMode = true
                        } else {
                            pairHost = endpoint.host
                            pairPort = endpoint.port.toString()
                        }
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(endpoint.name, fontWeight = FontWeight.Medium)
                            Text(
                                "${endpoint.host}:${endpoint.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilterChip(
                            selected = false,
                            onClick = {},
                            label = {
                                Text(
                                    if (endpoint.kind == DiscoveredEndpoint.Kind.CONNECT)
                                        "connect" else "pairing"
                                )
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Pairing
        Text("1 · Pair this phone (once per target)", fontWeight = FontWeight.SemiBold)
        Text(
            "On the target: Settings → Developer options → Wireless debugging → " +
                "\"Pair device with pairing code\". Enter the IP, pairing port and " +
                "6-digit code shown there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = pairHost,
            onValueChange = { pairHost = it.trim() },
            label = { Text("Target IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pairPort,
                onValueChange = { pairPort = it.filter { c -> c.isDigit() } },
                label = { Text("Pairing port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = pairCode,
                onValueChange = { pairCode = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Pairing code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                val port = pairPort.toIntOrNull()
                if (pairHost.isEmpty() || port == null || pairCode.length != 6) {
                    pairStatus = "Fill in the IP, pairing port and the 6-digit code first."
                    return@Button
                }
                pairing = true
                pairStatus = "Pairing…"
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            PairingClient.pair(context, pairHost, port, pairCode)
                        }
                        pairStatus = "Paired. This phone is now trusted by the target — " +
                            "you can connect below."
                        if (host.isEmpty()) host = pairHost
                    } catch (t: Throwable) {
                        pairStatus = "Pairing failed: ${t.message}"
                    } finally {
                        pairing = false
                    }
                }
            },
            enabled = !pairing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (pairing) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.height(0.dp))
            }
            Text("Pair")
        }
        pairStatus?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))

        // Connect
        Text("2 · Connect & mirror", fontWeight = FontWeight.SemiBold)
        Text(
            "The connect port is shown on the target's Wireless debugging screen " +
                "(it is different from the pairing port and changes over time). " +
                "Tap a discovered \"connect\" endpoint above to fill it in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = tlsMode,
                onClick = { tlsMode = true },
                label = { Text("Wireless debugging") }
            )
            FilterChip(
                selected = !tlsMode,
                onClick = { tlsMode = false },
                label = { Text("Legacy TCP/IP 5555") }
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim() },
            label = { Text("Target IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = connectPort,
            onValueChange = { connectPort = it.filter { c -> c.isDigit() } },
            label = { Text(if (tlsMode) "Connect port" else "Port (default 5555)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val port = connectPort.toIntOrNull() ?: if (tlsMode) null else 5555
                if (host.isNotEmpty() && port != null) {
                    TargetsStore.upsert(
                        context,
                        SavedTarget("", host, port, tlsMode, System.currentTimeMillis())
                    )
                    onConnect(host, port, tlsMode)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start mirroring")
        }
    }
}

// ----------------------------------------------------------------------- USB

@Composable
fun UsbScreen(
    onBack: () -> Unit,
    onConnect: (UsbDevice) -> Unit
) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(listUsbDevices(context)) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { devices = listUsbDevices(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        ScreenHeader(
            "USB / OTG",
            "Connect the target with an OTG cable and enable USB debugging on it.",
            onBack
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Attached devices", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { devices = listUsbDevices(context) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        if (devices.isEmpty()) {
            Text(
                "No USB device with an ADB interface found. Plug the target in, " +
                    "unlock it, and tap \"Allow\" if it asks about USB debugging.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        for (device in devices) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Usb, contentDescription = null)
                    Spacer(Modifier.padding(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.productName ?: "Android device",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "VID 0x${device.vendorId.toString(16).uppercase()} · " +
                                "PID 0x${device.productId.toString(16).uppercase()}" +
                                if (UsbTransport.isAdbDevice(device)) " · ADB" else " · no ADB interface",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (UsbTransport.isAdbDevice(device)) {
                                onConnect(device)
                            } else {
                                status = "That device does not expose an ADB interface."
                            }
                        }
                    ) { Text("Mirror") }
                }
            }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun listUsbDevices(context: Context): List<UsbDevice> {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    return usbManager.deviceList.values.toList()
}
