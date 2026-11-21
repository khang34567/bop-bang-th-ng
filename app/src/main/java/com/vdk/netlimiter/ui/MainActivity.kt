package com.vdk.netlimiter.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vdk.netlimiter.R
import com.vdk.netlimiter.core.DeviceManager
import com.vdk.netlimiter.core.NetworkScanner
import com.vdk.netlimiter.core.ProxyService
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvProxyStatus: TextView
    private lateinit var tvTotalStats: TextView
    private lateinit var tvProxyAddress: TextView
    private lateinit var tvAllIps: TextView
    private lateinit var btnCopyIp: Button
    private lateinit var btnToggleProxy: Button
    private lateinit var btnHelp: Button
    private lateinit var tvGlobalLimitValue: TextView
    private lateinit var seekGlobalLimit: SeekBar
    private lateinit var tvDevicesTitle: TextView
    private lateinit var btnScanDevices: Button
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var rvDevices: RecyclerView

    private lateinit var deviceAdapter: DeviceAdapter
    private val uiHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var isScanning = false
    private var currentPrimaryIp = "192.168.43.1"

    private val uiUpdater = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DeviceManager.init(this)
        requestNotificationPermissionIfNeeded()

        bindViews()
        setupGlobalLimitSlider()
        setupDeviceList()
        setupActions()
        refreshUi()
    }

    private fun bindViews() {
        tvProxyStatus = findViewById(R.id.tvProxyStatus)
        tvTotalStats = findViewById(R.id.tvTotalStats)
        tvProxyAddress = findViewById(R.id.tvProxyAddress)
        tvAllIps = findViewById(R.id.tvAllIps)
        btnCopyIp = findViewById(R.id.btnCopyIp)
        btnToggleProxy = findViewById(R.id.btnToggleProxy)
        btnHelp = findViewById(R.id.btnHelp)
        tvGlobalLimitValue = findViewById(R.id.tvGlobalLimitValue)
        seekGlobalLimit = findViewById(R.id.seekGlobalLimit)
        tvDevicesTitle = findViewById(R.id.tvDevicesTitle)
        btnScanDevices = findViewById(R.id.btnScanDevices)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        rvDevices = findViewById(R.id.rvDevices)
    }

    private fun setupGlobalLimitSlider() {
        val currentGlobal = DeviceManager.globalDefaultLimitKBps
        seekGlobalLimit.progress = currentGlobal.coerceAtMost(2000)
        renderGlobalLimitText(currentGlobal)

        seekGlobalLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = if (progress in 1..9) 10 else progress
                    DeviceManager.setGlobalLimit(value)
                    renderGlobalLimitText(value)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun renderGlobalLimitText(kb: Int) {
        if (kb <= 0) {
            tvGlobalLimitValue.text = "Không giới hạn"
        } else {
            val mbps = (kb * 8.0) / 1024.0
            tvGlobalLimitValue.text = String.format(Locale.US, "%d KB/s (~%.1f Mbps)", kb, mbps)
        }
    }

    private fun setupDeviceList() {
        deviceAdapter = DeviceAdapter(
            onConfigureClick = { snapshot ->
                DeviceControlDialog.show(this, snapshot) {
                    refreshUi()
                    Toast.makeText(this, "Đã lưu cấu hình cho ${snapshot.ip}", Toast.LENGTH_SHORT).show()
                }
            },
            onBlockClick = { snapshot ->
                val blocked = DeviceManager.toggleBlock(snapshot.ip)
                val msg = if (blocked) "Đã chặn mạng ${snapshot.getDisplayName()}" else "Đã mở mạng ${snapshot.getDisplayName()}"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        )
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter
        rvDevices.itemAnimator = null
    }

    private fun setupActions() {
        btnToggleProxy.setOnClickListener {
            val serviceIntent = Intent(this, ProxyService::class.java)
            if (ProxyService.isRunning) {
                stopService(serviceIntent)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
            uiHandler.postDelayed({ refreshUi() }, 250L)
        }

        btnCopyIp.setOnClickListener {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("VDK Proxy", "$currentPrimaryIp:${ProxyService.PORT}"))
            Toast.makeText(this, "Đã copy: $currentPrimaryIp:${ProxyService.PORT}", Toast.LENGTH_SHORT).show()
        }

        btnScanDevices.setOnClickListener {
            if (isScanning) return@setOnClickListener
            isScanning = true
            btnScanDevices.text = "Đang quét..."
            btnScanDevices.isEnabled = false

            bgExecutor.execute {
                NetworkScanner.scanHotspotSubnet { count ->
                    uiHandler.post {
                        isScanning = false
                        btnScanDevices.text = "Quét Hotspot"
                        btnScanDevices.isEnabled = true
                        refreshUi()
                        Toast.makeText(this, "Quét xong: tìm thấy $count thiết bị trong mạng", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnHelp.setOnClickListener {
            showHelpDialog()
        }
    }

    private fun refreshUi() {
        val info = NetworkScanner.getHotspotInfo()
        currentPrimaryIp = info.primaryIp
        tvProxyAddress.text = "$currentPrimaryIp : ${ProxyService.PORT}"
        tvAllIps.text = if (info.allLocalIps.size > 1) {
            "IP khả dụng khác: ${info.allLocalIps.drop(1).joinToString(", ")}"
        } else {
            "Nhập IP và Cổng 8080 vào mục Proxy Wi-Fi trên máy bắt ké"
        }

        if (ProxyService.isRunning) {
            tvProxyStatus.text = "● ĐANG CHẠY PROXY"
            tvProxyStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            btnToggleProxy.text = "DỪNG PROXY"
            btnToggleProxy.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_red))
            btnToggleProxy.setTextColor(Color.WHITE)
        } else {
            tvProxyStatus.text = "● ĐÃ TẮT"
            tvProxyStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            btnToggleProxy.text = "BẬT PROXY BÓP BĂNG THÔNG"
            btnToggleProxy.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_green))
            btnToggleProxy.setTextColor(Color.BLACK)
        }

        val speedKb = DeviceManager.totalSpeedBps.get() / 1024.0
        val totalMb = DeviceManager.totalAllDevicesBytes.get() / (1024.0 * 1024.0)
        tvTotalStats.text = String.format(Locale.US, "%.0f KB/s • %.1f MB", speedKb, totalMb)

        val snapshots = DeviceManager.getDeviceSnapshots()
        tvDevicesTitle.text = "Thiết bị trong mạng (${snapshots.size})"

        if (snapshots.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            rvDevices.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            rvDevices.visibility = View.VISIBLE
            deviceAdapter.submitList(snapshots)
        }
    }

    private fun showHelpDialog() {
        val msg = buildString {
            appendLine("Vì Android không Root không cho app can thiệp trực tiếp vào driver Hotspot, app dùng cơ chế Local Proxy (cổng 8080):")
            appendLine()
            appendLine("1. Bật Điểm phát sóng di động (Hotspot 4G) trên máy bạn.")
            appendLine("2. Bấm \"BẬT PROXY BÓP BĂNG THÔNG\" trong app.")
            appendLine("3. Trên máy bắt Wi-Fi (Android / iPhone / PC):")
            appendLine("   • Android: Cài đặt Wi-Fi -> chạm tên Wi-Fi -> Sửa -> Nâng cao -> Proxy: Thủ công -> nhập IP ($currentPrimaryIp) & Cổng 8080 -> Lưu.")
            appendLine("   • iPhone: Cài đặt Wi-Fi -> bấm chữ (i) -> Định cấu hình Proxy -> Thủ công -> nhập Máy chủ ($currentPrimaryIp) & Cổng 8080 -> Lưu.")
            appendLine("   • Windows PC: Settings -> Network & Internet -> Proxy -> Manual proxy -> nhập IP & Port 8080.")
            appendLine("4. Khi máy kia mở web/TikTok/YouTube, máy sẽ tự hiện trong danh sách với nhãn \"QUA PROXY\".")
            appendLine("5. Bấm \"Chọn mức bóp / Đổi tên\" để giới hạn tốc độ riêng hoặc bấm \"Chặn mạng\" để ngắt ngay lập tức!")
        }

        AlertDialog.Builder(this)
            .setTitle("Hướng dẫn sử dụng VDK Net Limiter Pro")
            .setMessage(msg)
            .setPositiveButton("Đã hiểu", null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiUpdater)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiUpdater)
    }

    override fun onDestroy() {
        bgExecutor.shutdownNow()
        super.onDestroy()
    }
}
