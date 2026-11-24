package com.droidbridge.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class DiscoveredMac(
    val host: String,
    val port: Int,
    val macDeviceId: String?
)

/**
 * Discovers the paired Mac across any Wi-Fi network without re-pairing by racing:
 * 1. Fast UDP LAN broadcast probe on port 47322 (works even when routers block mDNS, <10ms).
 * 2. Standard mDNS / Bonjour (_droidbridge._tcp) with WifiManager.MulticastLock.
 */
class DiscoveryManager(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val executor = Executors.newSingleThreadExecutor()

    suspend fun discoverMac(targetMacDeviceId: String, timeoutMs: Long = 2_500L): DiscoveredMac? =
        coroutineScope {
            val winner = CompletableDeferred<DiscoveredMac?>()

            val udpJob = async(Dispatchers.IO) {
                val res = probeUdpLan(targetMacDeviceId, timeoutMs)
                if (res != null) winner.complete(res)
                res
            }

            val mdnsJob = async(Dispatchers.IO) {
                val res = discoverMdns(targetMacDeviceId, timeoutMs)
                if (res != null) winner.complete(res)
                res
            }

            val result = withTimeoutOrNull(timeoutMs) {
                val first = async { winner.await() }
                val bothDone = async {
                    val u = udpJob.await()
                    val m = mdnsJob.await()
                    if (!winner.isCompleted) winner.complete(u ?: m)
                    u ?: m
                }
                val r = first.await()
                bothDone.cancel()
                r
            }

            udpJob.cancel()
            mdnsJob.cancel()
            result
        }

    /**
     * Broadcasts `DBP1|<targetMacDeviceId>` on UDP 47322 across 255.255.255.255 and
     * all active IPv4 interface broadcast addresses; returns immediately on `DBR1|<id>|<port>`.
     */
    private suspend fun probeUdpLan(targetMacDeviceId: String, timeoutMs: Long): DiscoveredMac? =
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 450
                }
                val probeBytes = "DBP1|$targetMacDeviceId".toByteArray(Charsets.UTF_8)
                val broadcastAddrs = collectBroadcastAddresses()
                val recvBuf = ByteArray(256)
                val deadline = System.currentTimeMillis() + timeoutMs

                while (System.currentTimeMillis() < deadline) {
                    for (addr in broadcastAddrs) {
                        runCatching {
                            val pkt = DatagramPacket(probeBytes, probeBytes.size, addr, Protocol.UDP_DISCOVERY_PORT)
                            socket.send(pkt)
                        }
                    }

                    val waitUntil = minOf(System.currentTimeMillis() + 450, deadline)
                    while (System.currentTimeMillis() < waitUntil) {
                        val resp = DatagramPacket(recvBuf, recvBuf.size)
                        try {
                            socket.receive(resp)
                            val msg = String(resp.data, 0, resp.length, Charsets.UTF_8).trim()
                            val parts = msg.split("|")
                            if (parts.size == 3 && parts[0] == "DBR1" && parts[1] == targetMacDeviceId) {
                                val port = parts[2].toIntOrNull()
                                val host = resp.address?.hostAddress
                                if (port != null && port in 1..65535 && !host.isNullOrBlank()) {
                                    BridgeLog.i("Discovery", "UDP probe found Mac at $host:$port")
                                    return@withContext DiscoveredMac(host, port, targetMacDeviceId)
                                }
                            }
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
                null
            } catch (e: Exception) {
                BridgeLog.w("Discovery", "UDP probe error: ${e.message}")
                null
            } finally {
                runCatching { socket?.close() }
            }
        }

    private fun collectBroadcastAddresses(): List<InetAddress> {
        val addrs = LinkedHashSet<InetAddress>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast is Inet4Address) {
                        addrs.add(bcast)
                    }
                }
            }
        }
        runCatching { addrs.add(InetAddress.getByName("255.255.255.255")) }
        return addrs.toList()
    }

    private suspend fun discoverMdns(targetMacDeviceId: String, timeoutMs: Long): DiscoveredMac? {
        val multicastLock = wifiManager?.createMulticastLock("DroidBridgeNsdLock")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                discoverMdnsInternal(targetMacDeviceId)
            }
        } finally {
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }
    }

    private suspend fun discoverMdnsInternal(targetMacDeviceId: String): DiscoveredMac? =
        suspendCancellableCoroutine { cont ->
            var stopped = false
            var listenerRef: NsdManager.DiscoveryListener? = null

            fun finish(result: DiscoveredMac?) {
                if (!stopped) {
                    stopped = true
                    listenerRef?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
                    if (cont.isActive) cont.resume(result)
                }
            }

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (stopped) return
                    resolveService(serviceInfo) { resolved ->
                        if (resolved != null) {
                            val txtId = resolved.macDeviceId
                            if (txtId == null || txtId == targetMacDeviceId) {
                                BridgeLog.i("Discovery", "mDNS found Mac at ${resolved.host}:${resolved.port}")
                                finish(resolved)
                            }
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    finish(null)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            listenerRef = listener

            cont.invokeOnCancellation {
                if (!stopped) {
                    stopped = true
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                }
            }

            try {
                nsdManager.discoverServices(
                    Protocol.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            } catch (_: Exception) {
                finish(null)
            }
        }

    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo, onResult: (DiscoveredMac?) -> Unit) {
        // Android 14+ (API 34+): registerServiceInfoCallback replaces deprecated resolveService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var callbackRef: NsdManager.ServiceInfoCallback? = null
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    onResult(null)
                }

                override fun onServiceUpdated(info: NsdServiceInfo) {
                    callbackRef?.let { runCatching { nsdManager.unregisterServiceInfoCallback(it) } }
                    val addr = info.hostAddresses.firstOrNull { it is Inet4Address }
                        ?: info.hostAddresses.firstOrNull()
                    val host = addr?.hostAddress
                    val id = info.attributes["id"]?.toString(Charsets.UTF_8)
                    if (host != null && info.port > 0) {
                        onResult(DiscoveredMac(host, info.port, id))
                    } else {
                        onResult(null)
                    }
                }

                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            callbackRef = cb
            runCatching { nsdManager.registerServiceInfoCallback(serviceInfo, executor, cb) }
                .onFailure { onResult(null) }
        } else {
            runCatching {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        onResult(null)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress
                        val id = info.attributes["id"]?.toString(Charsets.UTF_8)
                        if (host != null && info.port > 0) {
                            onResult(DiscoveredMac(host, info.port, id))
                        } else {
                            onResult(null)
                        }
                    }
                })
            }.onFailure { onResult(null) }
        }
    }
}
