package com.pocketstream.tv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.pocketstream.protocol.DiscoveryConstants
import com.pocketstream.protocol.PhoneInfo
import com.pocketstream.protocol.TvInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

class TvDiscoveryManager(
    private val context: Context,
    private val tvPort: Int = 8085,
    private val onPhoneDiscovered: (phoneIp: String, phonePort: Int, name: String) -> Unit,
    private val onPhoneDisconnected: () -> Unit = {}
) {
    companion object {
        private const val TAG = "TvDiscoveryManager"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var udpJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        acquireMulticastLock()
        startNsdRegistration()
        startNsdDiscovery()
        startUdpListener()
        broadcastTvPresence()
    }

    fun stop() {
        stopUdpListener()
        stopNsd()
        releaseMulticastLock()
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            // 1. First priority: active Ethernet (eth) or Wi-Fi (wlan) interfaces (essential for TV setups)
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("eth") || name.startsWith("wlan") || name.contains("wifi")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (ip != "127.0.0.1" && !ip.startsWith("169.254")) {
                                return ip
                            }
                        }
                    }
                }
            }
            // 2. Second priority: any non-loopback IPv4 interface excluding virtual adapters
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("p2p") || name.startsWith("rmnet")) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip != "127.0.0.1" && !ip.startsWith("169.254")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("PocketStreamTvMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun startNsdRegistration() {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "PocketStream-TV"
                serviceType = DiscoveryConstants.NSD_SERVICE_TYPE_TV
                port = tvPort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    Log.i(TAG, "NSD TV service registered: ${NsdServiceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD TV registration failed: $errorCode")
                }
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error starting NSD registration: ${e.message}")
        }
    }

    private fun startNsdDiscovery() {
        try {
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.i(TAG, "NSD discovery started for phones")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.i(TAG, "NSD found service: ${service.serviceName}")
                    if (service.serviceType.contains("pocketstream-phone")) {
                        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "NSD resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress ?: return
                                val port = serviceInfo.port
                                Log.i(TAG, "NSD resolved phone: $host:$port")
                                onPhoneDiscovered(host, port, "PocketStream Phone")
                            }
                        })
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            nsdManager?.discoverServices(
                DiscoveryConstants.NSD_SERVICE_TYPE_PHONE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error starting NSD discovery: ${e.message}")
        }
    }

    private fun startUdpListener() {
        udpJob?.cancel()
        udpJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create socket unbound first so reuseAddress is applied BEFORE bind
                val socket = try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(java.net.InetSocketAddress(DiscoveryConstants.UDP_DISCOVERY_PORT))
                    }
                } catch (_: Exception) {
                    DatagramSocket().apply { broadcast = true }
                }
                udpSocket = socket

                val buffer = ByteArray(2048)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val senderIp = packet.address.hostAddress ?: ""
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()

                    Log.d(TAG, "UDP received from $senderIp: $msg")
                    try {
                        val json = JSONObject(msg)
                        val type = json.optString("type")

                        if (type == "PHONE_DISCONNECT") {
                            Log.i(TAG, "UDP Phone announced disconnect: $msg")
                            onPhoneDisconnected()
                        } else if (type == "DISCOVER_TV" || type == "PHONE_ANNOUNCE") {
                            val phoneIp = json.optString("phoneIp", senderIp).ifEmpty { senderIp }
                            val phonePort = json.optInt("phonePort", 8080)
                            val name = json.optString("deviceName", "PocketStream Phone")

                            Log.i(TAG, "UDP Phone discovered: $phoneIp:$phonePort ($name)")
                            onPhoneDiscovered(phoneIp, phonePort, name)

                            // Respond with TV presence
                            val tvDeviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifEmpty { "Android TV" }
                            val replyJson = JSONObject().apply {
                                put("type", "TV_ANNOUNCE")
                                put("tvIp", getLocalIpAddress())
                                put("tvPort", tvPort)
                                put("deviceName", tvDeviceName)
                            }
                            val replyBytes = replyJson.toString().toByteArray(Charsets.UTF_8)
                            val replyPacket = DatagramPacket(replyBytes, replyBytes.size, packet.address, packet.port)
                            socket.send(replyPacket)
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "UDP listener stopped: ${e.message}")
                }
            }
        }
    }

    private fun getBroadcastTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.interfaceAddresses) {
                    val bcast = addr.broadcast
                    if (bcast != null && !targets.contains(bcast)) {
                        targets.add(bcast)
                    }
                }
            }
        } catch (_: Exception) {}
        return targets
    }

    fun broadcastTvPresence() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val localIp = getLocalIpAddress()
                val tvDeviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifEmpty { "Android TV" }
                val json = JSONObject().apply {
                    put("type", "TV_ANNOUNCE")
                    put("tvIp", localIp)
                    put("tvPort", tvPort)
                    put("deviceName", tvDeviceName)
                }
                val bytes = json.toString().toByteArray(Charsets.UTF_8)
                val targets = getBroadcastTargets()
                val socket = DatagramSocket()
                socket.broadcast = true
                for (target in targets) {
                    try {
                        val packet = DatagramPacket(
                            bytes,
                            bytes.size,
                            target,
                            DiscoveryConstants.UDP_DISCOVERY_PORT
                        )
                        socket.send(packet)
                    } catch (_: Exception) {}
                }
                socket.close()
                Log.i(TAG, "Broadcasted TV presence to ${targets.size} targets on port ${DiscoveryConstants.UDP_DISCOVERY_PORT}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed broadcasting TV presence: ${e.message}")
            }
        }
    }

    private fun stopUdpListener() {
        try {
            udpJob?.cancel()
            udpJob = null
            udpSocket?.close()
            udpSocket = null
        } catch (_: Exception) {}
    }

    private fun stopNsd() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        registrationListener = null
        discoveryListener = null
    }
}
