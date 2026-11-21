package com.vdk.netlimiter.model

data class DeviceSnapshot(
    val ip: String,
    val mac: String,
    val vendor: String,
    val customName: String,
    val isUsingProxy: Boolean,
    val isOnlineInLan: Boolean,
    val isBlocked: Boolean,
    val customLimitKBps: Int,
    val effectiveLimitKBps: Int,
    val activeConnections: Int,
    val currentSpeedBps: Long,
    val totalBytes: Long,
    val lastDomain: String,
    val lastSeenMs: Long
) {
    fun getDisplayName(): String {
        return when {
            customName.isNotBlank() -> customName
            vendor.isNotBlank() -> "$vendor ($ip)"
            else -> "Thiết bị $ip"
        }
    }
}
