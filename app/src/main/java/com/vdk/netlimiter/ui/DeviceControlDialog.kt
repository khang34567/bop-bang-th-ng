package com.vdk.netlimiter.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.vdk.netlimiter.R
import com.vdk.netlimiter.core.DeviceManager
import com.vdk.netlimiter.model.DeviceSnapshot
import java.util.Locale

object DeviceControlDialog {

    fun show(context: Context, device: DeviceSnapshot, onSaved: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_device_control, null)

        val tvTitle: TextView = view.findViewById(R.id.tvDialogTitle)
        val tvSub: TextView = view.findViewById(R.id.tvDialogSub)
        val etName: EditText = view.findViewById(R.id.etCustomName)
        val swBlock: Switch = view.findViewById(R.id.swBlockDevice)
        val tvLimitLabel: TextView = view.findViewById(R.id.tvDialogLimitLabel)
        val seekLimit: SeekBar = view.findViewById(R.id.seekDeviceLimit)

        val btnPreset30: Button = view.findViewById(R.id.btnPreset30)
        val btnPreset100: Button = view.findViewById(R.id.btnPreset100)
        val btnPreset300: Button = view.findViewById(R.id.btnPreset300)
        val btnPreset1000: Button = view.findViewById(R.id.btnPreset1000)
        val btnPresetUnlimited: Button = view.findViewById(R.id.btnPresetUnlimited)
        val btnPresetDefault: Button = view.findViewById(R.id.btnPresetDefault)

        val btnCancel: Button = view.findViewById(R.id.btnCancelDialog)
        val btnSave: Button = view.findViewById(R.id.btnSaveDialog)

        tvTitle.text = device.getDisplayName()
        val macPart = if (device.mac.isNotBlank()) " • MAC: ${device.mac}" else ""
        tvSub.text = "IP: ${device.ip}$macPart"
        etName.setText(device.customName)
        swBlock.isChecked = device.isBlocked

        var selectedLimitKBps = device.customLimitKBps

        fun renderLimitLabel() {
            tvLimitLabel.text = when {
                swBlock.isChecked -> "Trạng thái: ĐANG CHẶN TOÀN BỘ MẠNG (0 KB/s)"
                selectedLimitKBps == -1 -> "Giới hạn tốc độ: Theo mặc định chung"
                selectedLimitKBps == 0 -> "Giới hạn tốc độ: Không giới hạn (Max speed)"
                else -> {
                    val mbps = (selectedLimitKBps * 8.0) / 1024.0
                    String.format(Locale.US, "Giới hạn riêng: %d KB/s (~%.2f Mbps)", selectedLimitKBps, mbps)
                }
            }
            seekLimit.isEnabled = !swBlock.isChecked
        }

        seekLimit.progress = if (selectedLimitKBps > 0) selectedLimitKBps.coerceAtMost(3000) else 150
        renderLimitLabel()

        swBlock.setOnCheckedChangeListener { _, _ ->
            renderLimitLabel()
        }

        seekLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    selectedLimitKBps = if (progress < 10) 10 else progress
                    swBlock.isChecked = false
                    renderLimitLabel()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        fun applyPreset(kb: Int) {
            selectedLimitKBps = kb
            swBlock.isChecked = false
            if (kb > 0) seekLimit.progress = kb
            renderLimitLabel()
        }

        btnPreset30.setOnClickListener { applyPreset(30) }
        btnPreset100.setOnClickListener { applyPreset(100) }
        btnPreset300.setOnClickListener { applyPreset(300) }
        btnPreset1000.setOnClickListener { applyPreset(1024) }
        btnPresetUnlimited.setOnClickListener { applyPreset(0) }
        btnPresetDefault.setOnClickListener { applyPreset(-1) }

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            DeviceManager.updateDeviceRule(
                ip = device.ip,
                customName = etName.text.toString(),
                customLimitKBps = selectedLimitKBps,
                isBlocked = swBlock.isChecked
            )
            dialog.dismiss()
            onSaved()
        }

        dialog.show()
    }
}
