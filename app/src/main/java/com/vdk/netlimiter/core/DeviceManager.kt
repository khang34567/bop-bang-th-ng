package com.vdk.netlimiter.core

import android.content.Context
import android.content.SharedPreferences
import com.vdk.netlimiter.model.DeviceSnapshot
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object DeviceManager {

    private const val PREFS_NAME = "vdk_net_limiter_prefs"
    private const val KEY_GLOBAL_LIMIT = "global_default_limit_kbps"

    class DeviceRuntimeState(val ip: String) {
        @Volatile var mac: String = ""
        @Volatile var vendor: String = ""
        @Volatile var customName: String = ""
        @Volatile var isUsingProxy: Boolean = false
        @Volatile var isOnlineInLan: Boolean = false
        @Volatile var isBlocked: Boolean = false
        @Volatile var customLimitKBps: Int = -1
        @Volatile var lastDomain: String = ""
        @Volatile var lastSeenMs: Long = System.currentTimeMillis()
        @Volatile var currentSpeedBps: Long = 0L

        val activeConnections = AtomicInteger(0)
        val totalBytes = AtomicLong(0L)
        val windowBytes = AtomicLong(0L)
        val limiter = TokenBucketLimiter()
        val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    }

    private val devices = ConcurrentHashMap<String, DeviceRuntimeState>()
    private var prefs: SharedPreferences? = null

    @Volatile
    var globalDefaultLimitKBps: Int = 0

    val totalSpeedBps = AtomicLong(0L)
    val totalAllDevicesBytes = AtomicLong(0L)

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            globalDefaultLimitKBps = prefs?.getInt(KEY_GLOBAL_LIMIT, 0) ?: 0
        }
    }

    fun setGlobalLimit(kbPerSec: Int) {
        globalDefaultLimitKBps = kbPerSec.coerceAtLeast(0)
        prefs?.edit()?.putInt(KEY_GLOBAL_LIMIT, globalDefaultLimitKBps)?.apply()
        devices.values.forEach { state ->
            if (state.customLimitKBps == -1) {
                state.limiter.reset()
            }
        }
    }

    private fun getOrCreate(ip: String): DeviceRuntimeState {
        return devices.computeIfAbsent(ip) { key ->
            val state = DeviceRuntimeState(key)
            loadSavedRule(state)
            state
        }
    }

    private fun loadSavedRule(state: DeviceRuntimeState) {
        val p = prefs ?: return
        val ipKey = state.ip
        state.customName = p.getString("name_$ipKey", "") ?: ""
        state.isBlocked = p.getBoolean("block_$ipKey", false)
        state.customLimitKBps = p.getInt("limit_$ipKey", -1)
    }

    fun updateDeviceRule(ip: String, customName: String, customLimitKBps: Int, isBlocked: Boolean) {
        val state = getOrCreate(ip)
        state.customName = customName.trim()
        state.customLimitKBps = customLimitKBps
        state.isBlocked = isBlocked
        state.limiter.reset()

        prefs?.edit()?.apply {
            putString("name_$ip", state.customName)
            putBoolean("block_$ip", isBlocked)
            putInt("limit_$ip", customLimitKBps)
            apply()
        }

        if (isBlocked) {
            killDeviceConnections(ip)
        }
    }

    fun toggleBlock(ip: String): Boolean {
        val state = getOrCreate(ip)
        val newBlocked = !state.isBlocked
        updateDeviceRule(ip, state.customName, state.customLimitKBps, newBlocked)
        return newBlocked
    }

    fun killDeviceConnections(ip: String) {
        val state = devices[ip] ?: return
        val copy = state.openSockets.toList()
        copy.forEach { sock ->
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
        state.openSockets.clear()
    }

    fun registerLanDevice(ip: String, mac: String, vendor: String) {
        val state = getOrCreate(ip)
        state.isOnlineInLan = true
        if (mac.isNotBlank()) state.mac = mac.uppercase()
        if (vendor.isNotBlank()) state.vendor = vendor
        state.lastSeenMs = System.currentTimeMillis()
    }

    fun onProxyConnectionStart(ip: String, socket: Socket): DeviceRuntimeState {
        val state = getOrCreate(ip)
        state.isUsingProxy = true
        state.isOnlineInLan = true
        state.lastSeenMs = System.currentTimeMillis()
        state.activeConnections.incrementAndGet()
        state.openSockets.add(socket)
        return state
    }

    fun attachRemoteSocket(ip: String, remote: Socket) {
        devices[ip]?.openSockets?.add(remote)
    }

    fun onProxyConnectionEnd(ip: String, client: Socket, remote: Socket?) {
        val state = devices[ip] ?: return
        state.openSockets.remove(client)
        if (remote != null) state.openSockets.remove(remote)
        if (state.activeConnections.decrementAndGet() < 0) {
            state.activeConnections.set(0)
        }
    }

    fun recordDomain(ip: String, host: String) {
        if (host.isBlank()) return
        val state = devices[ip] ?: return
        state.lastDomain = host
        state.lastSeenMs = System.currentTimeMillis()
    }

    fun recordBytes(ip: String, bytes: Int) {
        if (bytes <= 0) return
        val state = devices[ip] ?: return
        state.totalBytes.addAndGet(bytes.toLong())
        state.windowBytes.addAndGet(bytes.toLong())
        state.lastSeenMs = System.currentTimeMillis()
        totalAllDevicesBytes.addAndGet(bytes.toLong())
    }

    fun isBlocked(ip: String): Boolean {
        return devices[ip]?.isBlocked == true
    }

    fun getEffectiveLimitBytesPerSec(ip: String): Long {
        val state = devices[ip] ?: return globalDefaultLimitKBps * 1024L
        if (state.isBlocked) return -1L
        val kb = when {
            state.customLimitKBps > 0 -> state.customLimitKBps
            state.customLimitKBps == 0 -> 0
            else -> globalDefaultLimitKBps
        }
        return kb * 1024L
    }

    fun getLimiter(ip: String): TokenBucketLimiter {
        return getOrCreate(ip).limiter
    }

    fun tickOneSecond() {
        var totalSpeed = 0L
        devices.values.forEach { state ->
            val bps = state.windowBytes.getAndSet(0L)
            state.currentSpeedBps = bps
            totalSpeed += bps
        }
        totalSpeedBps.set(totalSpeed)
    }

    fun getDeviceSnapshots(): List<DeviceSnapshot> {
        return devices.values.map { s ->
            val effKB = when {
                s.isBlocked -> 0
                s.customLimitKBps > 0 -> s.customLimitKBps
                s.customLimitKBps == 0 -> 0
                else -> globalDefaultLimitKBps
            }
            DeviceSnapshot(
                ip = s.ip,
                mac = s.mac,
                vendor = s.vendor,
                customName = s.customName,
                isUsingProxy = s.isUsingProxy,
                isOnlineInLan = s.isOnlineInLan,
                isBlocked = s.isBlocked,
                customLimitKBps = s.customLimitKBps,
                effectiveLimitKBps = effKB,
                activeConnections = s.activeConnections.get(),
                currentSpeedBps = s.currentSpeedBps,
                totalBytes = s.totalBytes.get(),
                lastDomain = s.lastDomain,
                lastSeenMs = s.lastSeenMs
            )
        }.sortedWith(
            compareByDescending<DeviceSnapshot> { it.isUsingProxy }
                .thenByDescending { it.isBlocked }
                .thenByDescending { it.currentSpeedBps }
                .thenByDescending { it.lastSeenMs }
        )
    }
}
