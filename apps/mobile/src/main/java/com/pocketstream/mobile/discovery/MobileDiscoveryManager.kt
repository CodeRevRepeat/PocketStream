package com.pocketstream.mobile.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.pocketstream.mobile.server.NetworkUtils
import com.pocketstream.protocol.DiscoveryConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MobileDiscoveryManager(
    private val context: Context,
    private val phonePort: Int = 8080,
    private val onTvDiscovered: (tvIp: String, tvPort: Int, tvName: String) -> Unit
) {
    companion object {
        private const val TAG = "MobileDiscoveryMgr"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var udpJob: Job? = null
    private var broadcastJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        acquireMulticastLock()
        startNsdRegistration()
        startNsdDiscovery()
        startUdpListener()
        startPeriodicUdpBroadcast()
    }

    fun stop() {
        broadcastJob?.cancel()
        broadcastJob = null
        stopUdpListener()
        stopNsd()
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("PocketStreamMobileMulticastLock")?.apply {
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
                serviceName = "PocketStream-Phone"
                serviceType = DiscoveryConstants.NSD_SERVICE_TYPE_PHONE
                port = phonePort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "NSD Phone service registered: ${serviceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD Phone registration failed: $errorCode")
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
                    Log.i(TAG, "NSD discovery started for TVs")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.i(TAG, "NSD found service: ${service.serviceName}")
                    if (service.serviceType.contains("pocketstream-tv")) {
                        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "NSD resolve TV failed: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress ?: return
                                val port = serviceInfo.port
                                Log.i(TAG, "NSD resolved TV: $host:$port")
                                val tvName = serviceInfo.serviceName?.takeIf { it.isNotBlank() } ?: "Android TV"
                                onTvDiscovered(host, port, tvName)
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
                DiscoveryConstants.NSD_SERVICE_TYPE_TV,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error starting NSD TV discovery: ${e.message}")
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

                        if (type == "TV_ANNOUNCE") {
                            val tvIp = json.optString("tvIp", senderIp).ifEmpty { senderIp }
                            val tvPort = json.optInt("tvPort", 8085)
                            val name = json.optString("deviceName", "Android TV")

                            Log.i(TAG, "UDP TV discovered: $tvIp:$tvPort ($name)")
                            onTvDiscovered(tvIp, tvPort, name)
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

    fun broadcastDiscoverTv() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val localIp = NetworkUtils.getLocalWifiIpAddress()
                val phoneName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifEmpty { "PocketStream Phone" }
                val json = JSONObject().apply {
                    put("type", "DISCOVER_TV")
                    put("phoneIp", localIp)
                    put("phonePort", phonePort)
                    put("deviceName", "$phoneName / PocketStream")
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
                Log.i(TAG, "Broadcasted DISCOVER_TV to ${targets.size} targets on port ${DiscoveryConstants.UDP_DISCOVERY_PORT}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending DISCOVER_TV: ${e.message}")
            }
        }
    }

    fun broadcastPhoneDisconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val localIp = NetworkUtils.getLocalWifiIpAddress()
                val json = JSONObject().apply {
                    put("type", "PHONE_DISCONNECT")
                    put("phoneIp", localIp)
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
                Log.i(TAG, "Broadcasted PHONE_DISCONNECT to ${targets.size} targets on port ${DiscoveryConstants.UDP_DISCOVERY_PORT}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending PHONE_DISCONNECT: ${e.message}")
            }
        }
    }

    private fun startPeriodicUdpBroadcast() {
        broadcastJob?.cancel()
        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            var attempts = 0
            while (isActive && attempts < 10) {
                broadcastDiscoverTv()
                delay(if (attempts < 3) 1500L else 5000L)
                attempts++
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
