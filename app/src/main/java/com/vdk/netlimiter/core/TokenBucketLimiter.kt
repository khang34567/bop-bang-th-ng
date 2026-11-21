package com.vdk.netlimiter.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Bộ giới hạn băng thông dùng chung cho TẤT CẢ các socket của cùng một địa chỉ IP.
 * Sử dụng thuật toán Paced Leaky Bucket (GCRA) thread-safe để đảm bảo dù thiết bị mở
 * 10-20 luồng tải song song thì tổng tốc độ vẫn bị kìm đúng mức KB/s đã cài đặt.
 */
class TokenBucketLimiter {
    private var nextAvailableTimeNs: Long = 0L
    private val lock = Any()
    private val generation = AtomicInteger(0)

    fun acquire(bytes: Int, limitBytesPerSec: Long, shouldContinue: () -> Boolean) {
        if (bytes <= 0 || limitBytesPerSec <= 0L) return

        val currentGen = generation.get()
        val waitMs: Long

        synchronized(lock) {
            val now = System.nanoTime()
            val minTime = now - 200_000_000L
            if (nextAvailableTimeNs < minTime) {
                nextAvailableTimeNs = minTime
            }
            val durationNs = (bytes.toLong() * 1_000_000_000L) / limitBytesPerSec
            val scheduled = nextAvailableTimeNs
            nextAvailableTimeNs = scheduled + durationNs

            val diffNs = scheduled - now
            waitMs = if (diffNs > 0L) diffNs / 1_000_000L else 0L
        }

        if (waitMs > 0L) {
            var remaining = waitMs
            while (remaining > 0L && shouldContinue() && generation.get() == currentGen) {
                val step = if (remaining > 80L) 80L else remaining
                try {
                    Thread.sleep(step)
                } catch (_: InterruptedException) {
                    return
                }
                remaining -= step
            }
        }
    }

    fun reset() {
        generation.incrementAndGet()
        synchronized(lock) {
            nextAvailableTimeNs = 0L
        }
    }
}
