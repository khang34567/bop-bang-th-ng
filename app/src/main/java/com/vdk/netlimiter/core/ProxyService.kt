package com.vdk.netlimiter.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vdk.netlimiter.ui.MainActivity
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ProxyService : Service() {

    companion object {
        const val PORT = 8080
        const val CHANNEL_ID = "VDKProxyServiceChannel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.vdk.netlimiter.ACTION_STOP"

        @Volatile
        var isRunning: Boolean = false
    }

    private var serverSocket: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val workerPool = Executors.newCachedThreadPool()
    private var tickerPool: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        DeviceManager.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            isRunning = true
            acquireWakeLock()
            startForegroundWithNotif()
            startTicker()
            workerPool.execute { runServerLoop() }
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VDKNetLimiter::ProxyWakeLock")
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
        } catch (_: Exception) {
        }
    }

    private fun createNotification(subtitle: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VDK Net Limiter Proxy",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ProxyService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VDK Net Limiter Pro • Cổng $PORT")
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Tắt Proxy", stopPending)
            .build()
    }

    private fun startForegroundWithNotif() {
        val ip = NetworkScanner.getHotspotInfo().primaryIp
        val notif = createNotification("Proxy đang chạy tại $ip:$PORT")
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notif, fgsType)
    }

    private fun startTicker() {
        tickerPool = Executors.newSingleThreadScheduledExecutor()
        var tickCount = 0
        tickerPool?.scheduleAtFixedRate({
            try {
                DeviceManager.tickOneSecond()
                tickCount++
                if (tickCount % 3 == 0 && isRunning) {
                    val speedKb = DeviceManager.totalSpeedBps.get() / 1024
                    val proxyCount = DeviceManager.getDeviceSnapshots().count { it.isUsingProxy }
                    val notif = createNotification("Đang quản lý $proxyCount máy • Tổng tốc độ: $speedKb KB/s")
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIF_ID, notif)
                }
            } catch (_: Exception) {
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun runServerLoop() {
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", PORT))
            }
            while (isRunning) {
                val client = serverSocket?.accept() ?: break
                workerPool.execute { handleClient(client) }
            }
        } catch (_: Exception) {
        } finally {
            isRunning = false
        }
    }

    private fun handleClient(client: Socket) {
        val clientIp = client.inetAddress?.hostAddress ?: "unknown"
        DeviceManager.onProxyConnectionStart(clientIp, client)
        var remote: Socket? = null

        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true

            if (DeviceManager.isBlocked(clientIp)) {
                sendForbidden(client.getOutputStream())
                return
            }

            val clientIn = BufferedInputStream(client.getInputStream(), 8192)
            val clientOut = client.getOutputStream()

            val requestLine = readHeaderLine(clientIn) ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 3) return

            val method = parts[0].uppercase()
            val target = parts[1]
            val httpVersion = parts[2]

            if (method == "CONNECT") {
                val hostPort = target.split(":")
                val host = hostPort[0]
                val port = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443
                DeviceManager.recordDomain(clientIp, host)

                while (true) {
                    val line = readHeaderLine(clientIn)
                    if (line.isNullOrEmpty()) break
                }

                if (DeviceManager.isBlocked(clientIp)) {
                    sendForbidden(clientOut)
                    return
                }

                val remoteSocket = Socket()
                remote = remoteSocket
                remoteSocket.connect(InetSocketAddress(host, port), 15_000)
                remoteSocket.soTimeout = 30_000
                remoteSocket.tcpNoDelay = true
                DeviceManager.attachRemoteSocket(clientIp, remoteSocket)

                clientOut.write("HTTP/1.1 200 Connection Established
Proxy-Agent: VDK-Net-Limiter

".toByteArray())
                clientOut.flush()

                val remoteIn = remoteSocket.getInputStream()
                val remoteOut = remoteSocket.getOutputStream()

                val uploadFuture = workerPool.submit {
                    try {
                        pipeThrottled(clientIp, clientIn, remoteOut)
                    } finally {
                        try { remoteSocket.shutdownOutput() } catch (_: Exception) {}
                    }
                }
                val downloadFuture = workerPool.submit {
                    try {
                        pipeThrottled(clientIp, remoteIn, clientOut)
                    } finally {
                        try { client.shutdownOutput() } catch (_: Exception) {}
                    }
                }

                try { uploadFuture.get() } catch (_: Exception) {}
                try { downloadFuture.get() } catch (_: Exception) {}

            } else {
                val parsedUrl = try {
                    URL(target)
                } catch (_: Exception) {
                    return
                }
                val host = parsedUrl.host
                val port = if (parsedUrl.port != -1) parsedUrl.port else 80
                val path = parsedUrl.file.ifEmpty { "/" }
                DeviceManager.recordDomain(clientIp, host)

                val headerBuilder = StringBuilder()
                headerBuilder.append("$method $path $httpVersion
")

                var contentLength = 0L
                var hasHostHeader = false
                while (true) {
                    val line = readHeaderLine(clientIn)
                    if (line.isNullOrEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("proxy-connection:") || lower.startsWith("proxy-authorization:")) {
                        continue
                    }
                    if (lower.startsWith("host:")) hasHostHeader = true
                    if (lower.startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                    }
                    headerBuilder.append(line).append("
")
                }
                if (!hasHostHeader) {
                    headerBuilder.append("Host: $host
")
                }
                headerBuilder.append("Connection: close

")

                if (DeviceManager.isBlocked(clientIp)) {
                    sendForbidden(clientOut)
                    return
                }

                val remoteSocket = Socket()
                remote = remoteSocket
                remoteSocket.connect(InetSocketAddress(host, port), 15_000)
                remoteSocket.soTimeout = 30_000
                DeviceManager.attachRemoteSocket(clientIp, remoteSocket)

                val remoteOut = remoteSocket.getOutputStream()
                remoteOut.write(headerBuilder.toString().toByteArray())

                if (contentLength > 0L) {
                    pipeFixedLength(clientIp, clientIn, remoteOut, contentLength)
                }
                remoteOut.flush()

                pipeThrottled(clientIp, remoteSocket.getInputStream(), clientOut)
            }
        } catch (_: Exception) {
        } finally {
            DeviceManager.onProxyConnectionEnd(clientIp, client, remote)
            try { client.close() } catch (_: Exception) {}
            try { remote?.close() } catch (_: Exception) {}
        }
    }

    private fun sendForbidden(out: OutputStream) {
        try {
            val body = "<html><body><h2>Access Blocked by VDK Net Limiter</h2></body></html>"
            val resp = "HTTP/1.1 403 Forbidden
Content-Type: text/html
Content-Length: ${body.length}
Connection: close

$body"
            out.write(resp.toByteArray())
            out.flush()
        } catch (_: Exception) {
        }
    }

    private fun pipeFixedLength(clientIp: String, input: InputStream, output: OutputStream, totalLength: Long) {
        val buffer = ByteArray(4096)
        var remaining = totalLength
        val limiter = DeviceManager.getLimiter(clientIp)
        while (remaining > 0 && isRunning && !DeviceManager.isBlocked(clientIp)) {
            val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            val limit = DeviceManager.getEffectiveLimitBytesPerSec(clientIp)
            if (limit < 0L) break
            if (limit > 0L) {
                limiter.acquire(read, limit) { isRunning && !DeviceManager.isBlocked(clientIp) }
            }
            output.write(buffer, 0, read)
            DeviceManager.recordBytes(clientIp, read)
            remaining -= read
        }
    }

    private fun pipeThrottled(clientIp: String, input: InputStream, output: OutputStream) {
        val largeBuffer = ByteArray(8192)
        val limiter = DeviceManager.getLimiter(clientIp)

        try {
            while (isRunning) {
                val limit = DeviceManager.getEffectiveLimitBytesPerSec(clientIp)
                if (limit < 0L) break

                val maxChunk = when {
                    limit in 1L..(80 * 1024L) -> 1024
                    limit in (80 * 1024L + 1)..(250 * 1024L) -> 2048
                    else -> largeBuffer.size
                }

                val read = input.read(largeBuffer, 0, maxChunk)
                if (read == -1) break

                if (limit > 0L) {
                    limiter.acquire(read, limit) { isRunning && !DeviceManager.isBlocked(clientIp) }
                }

                if (DeviceManager.isBlocked(clientIp)) break

                output.write(largeBuffer, 0, read)
                output.flush()
                DeviceManager.recordBytes(clientIp, read)
            }
        } catch (_: Exception) {
        }
    }

    private fun readHeaderLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '
'.code) {
                if (prev == ''.code && sb.isNotEmpty()) {
                    sb.setLength(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(c.toChar())
            prev = c
            if (sb.length > 8192) return sb.toString()
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { tickerPool?.shutdownNow() } catch (_: Exception) {}
        try { workerPool.shutdownNow() } catch (_: Exception) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
