package com.vdk.netlimiter.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object NetworkScanner {

    data class HotspotInfo(
        val primaryIp: String,
        val allLocalIps: List<String>
    )

    private val whitespaceRegex = Regex("\\s+")

    private val ouiMap = mapOf(
        "00:1A:11" to "Google",
        "3C:5A:B4" to "Google",
        "DC:A6:32" to "Raspberry Pi",
        "24:0A:C4" to "Espressif",
        "30:AE:A4" to "Espressif",
        "A4:C3:F0" to "Intel PC",
        "00:1B:21" to "Intel PC",
        "D8:BB:C1" to "PC / Realtek",
        "00:E0:4C" to "PC / Realtek",
        "BC:54:2F" to "Intel PC",
        "AC:BC:32" to "Apple",
        "F0:18:98" to "Apple",
        "28:CF:E9" to "Apple",
        "A4:83:E7" to "Apple",
        "DC:2B:2A" to "Apple",
        "00:16:32" to "Samsung",
        "28:39:5E" to "Samsung",
        "5C:3C:27" to "Samsung",
        "84:25:DB" to "Samsung",
        "A0:CC:2B" to "Samsung",
        "CC:07:AB" to "Samsung",
        "64:CC:2E" to "Xiaomi",
        "74:23:44" to "Xiaomi",
        "28:E3:1F" to "Xiaomi",
        "50:64:2B" to "Xiaomi",
        "C8:58:C0" to "OPPO",
        "A4:3B:FA" to "OPPO",
        "1C:77:F6" to "OPPO",
        "2C:5B:B8" to "Vivo",
        "E4:5A:A2" to "Vivo",
        "88:36:5F" to "Realme",
        "48:46:FB" to "Huawei",
        "00:E0:FC" to "Huawei"
    )

    fun getHotspotInfo(): HotspotInfo {
        val candidates = mutableListOf<Pair<Int, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val priority = when {
                    name.contains("swlan") || name.contains("ap") || name.contains("softap") -> 1
                    name.contains("wlan") -> 2
                    name.contains("rndis") || name.contains("usb") -> 3
                    else -> 4
                }
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            candidates.add(priority to ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val sorted = candidates.sortedBy { it.first }.map { it.second }.distinct()
        val primary = sorted.firstOrNull() ?: "192.168.43.1"
        return HotspotInfo(primary, sorted)
    }

    fun lookupVendor(mac: String): String {
        if (mac.length < 8) return ""
        val prefix = mac.uppercase().substring(0, 8)
        return ouiMap[prefix] ?: ""
    }

    fun readNeighborTable(): Map<String, String> {
        val result = mutableMapOf<String, String>()

        try {
            val arpFile = File("/proc/net/arp")
            if (arpFile.exists() && arpFile.canRead()) {
                arpFile.readLines().drop(1).forEach { line ->
                    val parts = line.trim().split(whitespaceRegex)
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        if (mac != "00:00:00:00:00:00" && mac.contains(":")) {
                            result[ip] = mac.uppercase()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        try {
            val proc = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show"))
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line?.trim() ?: continue
                    val parts = text.split(whitespaceRegex)
                    if (parts.isNotEmpty() && parts[0].count { it == '.' } == 3) {
                        val ip = parts[0]
                        val llIdx = parts.indexOf("lladdr")
                        val state = parts.lastOrNull() ?: ""
                        if (state != "FAILED" && llIdx != -1 && llIdx + 1 < parts.size) {
                            val mac = parts[llIdx + 1]
                            if (mac.contains(":")) {
                                result[ip] = mac.uppercase()
                            }
                        }
                    }
                }
            }
            proc.destroy()
        } catch (_: Exception) {
        }

        return result
    }

    fun scanHotspotSubnet(onFinished: (Int) -> Unit) {
        val info = getHotspotInfo()
        val ownIp = info.primaryIp
        val parts = ownIp.split(".")
        if (parts.size != 4) {
            onFinished(0)
            return
        }
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."

        val initialNeighbors = readNeighborTable()
        initialNeighbors.forEach { (ip, mac) ->
            if (ip != ownIp && ip.startsWith(prefix)) {
                DeviceManager.registerLanDevice(ip, mac, lookupVendor(mac))
            }
        }

        val pool = Executors.newFixedThreadPool(32)
        val discoveredIps = mutableListOf<String>()
        for (i in 1..254) {
            val targetIp = "$prefix$i"
            if (targetIp == ownIp) continue
            pool.execute {
                try {
                    val addr = InetAddress.getByName(targetIp)
                    if (addr.isReachable(300)) {
                        synchronized(discoveredIps) {
                            discoveredIps.add(targetIp)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }

        val updatedNeighbors = readNeighborTable()
        val allFound = (discoveredIps + updatedNeighbors.keys.filter { it.startsWith(prefix) && it != ownIp }).distinct()
        allFound.forEach { ip ->
            val mac = updatedNeighbors[ip] ?: ""
            DeviceManager.registerLanDevice(ip, mac, lookupVendor(mac))
        }
        onFinished(allFound.size)
    }
}
