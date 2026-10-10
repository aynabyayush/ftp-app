package com.ayush.ftpclient

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            val fallbackAddresses = mutableListOf<String>()

            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue

                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host != "127.0.0.1") {
                            val name = intf.name.lowercase()
                            // Prioritize Wi-Fi and Hotspot interfaces
                            if (name.contains("wlan") || name.contains("ap") || name.contains("hotspot") || name.contains("swlan") || name.contains("p2p")) {
                                return host
                            }
                            fallbackAddresses.add(host)
                        }
                    }
                }
            }

            // Fallback to any active non-loopback IPv4 interface
            if (fallbackAddresses.isNotEmpty()) {
                return fallbackAddresses.first()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}
