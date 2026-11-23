package app.scrcpy.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** A wireless-debugging endpoint advertised by a device over mDNS. */
data class DiscoveredEndpoint(
    val kind: Kind,
    val name: String,
    val host: String,
    val port: Int
) {
    enum class Kind { CONNECT, PAIRING }
}

/**
 * Discovers wireless debugging services with Android's NsdManager:
 * `_adb-tls-connect._tcp` (connect endpoints) and `_adb-tls-pairing._tcp`
 * (pairing endpoints). Resolutions are performed one at a time from a
 * queue, which is the only pattern NsdManager reliably supports.
 */
class MdnsDiscovery(
    context: Context,
    private val onFound: (DiscoveredEndpoint) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<NsdServiceInfo>()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        queue.clear()
        startDiscovery(SERVICE_CONNECT)
        startDiscovery(SERVICE_PAIRING)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        for (listener in listeners) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: Throwable) {
            }
        }
        listeners.clear()
    }

    private fun startDiscovery(serviceType: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo != null && running.get()) {
                    queue.offer(serviceInfo)
                    resolveNext()
                }
            }
        }
        listeners.add(listener)
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
        }
    }

    private val resolving = AtomicBoolean(false)

    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val next = queue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        @Suppress("DEPRECATION")
        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                resolving.set(false)
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                resolving.set(false)
                if (serviceInfo != null && running.get()) {
                    val host = serviceInfo.host?.hostAddress
                    if (host != null) {
                        val kind = if (serviceInfo.serviceType.contains("pairing")) {
                            DiscoveredEndpoint.Kind.PAIRING
                        } else {
                            DiscoveredEndpoint.Kind.CONNECT
                        }
                        val endpoint = DiscoveredEndpoint(
                            kind, serviceInfo.serviceName ?: host, host, serviceInfo.port
                        )
                        handler.post { onFound(endpoint) }
                    }
                }
                resolveNext()
            }
        })
    }

    companion object {
        const val SERVICE_CONNECT = "_adb-tls-connect._tcp"
        const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"
    }
}
