package com.pocketstream.mobile.server

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalWifiIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "127.0.0.1"
            
            // 1. First priority: Wi-Fi STA, Wi-Fi AP/Hotspot, or USB/Ethernet LAN interfaces
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.contains("wlan") || name.contains("wifi") || name.startsWith("ap") || name.startsWith("softap") || name.startsWith("eth")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (host != "127.0.0.1" && !host.startsWith("169.254")) {
                                return host
                            }
                        }
                    }
                }
            }

            // 2. Second priority: any non-cellular, non-VPN LAN IPv4 address
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ||
                    name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy") || name.startsWith("p2p")) {
                    continue
                }
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host != "127.0.0.1" && !host.startsWith("169.254")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}