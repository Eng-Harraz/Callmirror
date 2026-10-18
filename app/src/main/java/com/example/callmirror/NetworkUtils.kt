package com.example.callmirror

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.io.BufferedReader
import java.io.FileReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Finds local IPv4 address across active Wi-Fi, Ethernet, or Hotspot/Tethering network interfaces.
     */
    fun getLocalIpAddress(context: Context): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Prioritize hotspot bridge (ap_br, wlan, rndis, etc.)
            val sortedInterfaces = interfaces.sortedByDescending { 
                it.name.contains("ap") || it.name.contains("wlan") 
            }

            for (intf in sortedInterfaces) {
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.") && !host.startsWith("10.150.")) {
                            return host
                        }
                    }
                }
            }

            // Fallback attempt: WifiManager formatIpAddress
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (wifiIp != 0) {
                @Suppress("DEPRECATION")
                return Formatter.formatIpAddress(wifiIp)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Reads connected client IPs from ARP table on hotspot device.
     */
    fun getConnectedHotspotClientIp(): String? {
        try {
            val file = java.io.File("/proc/net/arp")
            if (!file.canRead()) return null
            val reader = BufferedReader(FileReader(file))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split("\\s+".toRegex())
                if (parts.size >= 4 && parts[0] != "IP" && parts[2] == "0x2") {
                    val ip = parts[0]
                    if (!ip.startsWith("127.") && !ip.startsWith("0.")) {
                        reader.close()
                        return ip
                    }
                }
            }
            reader.close()
        } catch (ignored: Exception) {}
        return null
    }

    /**
     * Checks if device is connected to a network.
     */
    fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
