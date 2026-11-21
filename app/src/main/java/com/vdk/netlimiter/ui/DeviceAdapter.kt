package com.vdk.netlimiter.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vdk.netlimiter.R
import com.vdk.netlimiter.model.DeviceSnapshot
import java.util.Locale

class DeviceAdapter(
    private val onConfigureClick: (DeviceSnapshot) -> Unit,
    private val onBlockClick: (DeviceSnapshot) -> Unit
) : ListAdapter<DeviceSnapshot, DeviceAdapter.DeviceVH>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<DeviceSnapshot>() {
        override fun areItemsTheSame(oldItem: DeviceSnapshot, newItem: DeviceSnapshot): Boolean {
            return oldItem.ip == newItem.ip
        }

        override fun areContentsTheSame(oldItem: DeviceSnapshot, newItem: DeviceSnapshot): Boolean {
            return oldItem == newItem
        }
    }

    class DeviceVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvDeviceName)
        val tvBadge: TextView = itemView.findViewById(R.id.tvDeviceBadge)
        val tvMeta: TextView = itemView.findViewById(R.id.tvDeviceMeta)
        val tvDomain: TextView = itemView.findViewById(R.id.tvDeviceDomain)
        val tvSpeed: TextView = itemView.findViewById(R.id.tvDeviceSpeed)
        val tvLimit: TextView = itemView.findViewById(R.id.tvDeviceLimit)
        val tvData: TextView = itemView.findViewById(R.id.tvDeviceData)
        val btnConfigure: Button = itemView.findViewById(R.id.btnConfigureDevice)
        val btnBlock: Button = itemView.findViewById(R.id.btnBlockDevice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceVH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceVH(view)
    }

    override fun onBindViewHolder(holder: DeviceVH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        holder.tvName.text = item.getDisplayName()

        val macText = if (item.mac.isNotBlank()) " • MAC: ${item.mac}" else ""
        val connText = if (item.activeConnections > 0) " • ${item.activeConnections} luồng" else ""
        holder.tvMeta.text = "IP: ${item.ip}$macText$connText"

        if (item.lastDomain.isNotBlank()) {
            holder.tvDomain.visibility = View.VISIBLE
            holder.tvDomain.text = "Truy cập gần nhất: ${item.lastDomain}"
        } else if (!item.isUsingProxy) {
            holder.tvDomain.visibility = View.VISIBLE
            holder.tvDomain.text = "Đang dùng mạng trực tiếp (chưa cài Proxy nên chưa bóp được)"
        } else {
            holder.tvDomain.visibility = View.GONE
        }

        when {
            item.isBlocked -> {
                holder.tvBadge.text = "ĐÃ CHẶN"
                holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_blocked)
                holder.tvBadge.setTextColor(ContextCompat.getColor(ctx, R.color.accent_red))
            }
            !item.isUsingProxy -> {
                holder.tvBadge.text = "CHƯA CÀI PROXY"
                holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_direct)
                holder.tvBadge.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            item.effectiveLimitKBps > 0 -> {
                holder.tvBadge.text = "ĐANG BÓP"
                holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_limited)
                holder.tvBadge.setTextColor(ContextCompat.getColor(ctx, R.color.accent_amber))
            }
            else -> {
                holder.tvBadge.text = "QUA PROXY"
                holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_active)
                holder.tvBadge.setTextColor(ContextCompat.getColor(ctx, R.color.accent_green))
            }
        }

        holder.tvSpeed.text = "⚡ ${formatSpeed(item.currentSpeedBps)}"
        holder.tvData.text = formatBytes(item.totalBytes)

        holder.tvLimit.text = when {
            item.isBlocked -> "Mức: 0 KB/s (Chặn)"
            item.effectiveLimitKBps > 0 -> "Mức: ${item.effectiveLimitKBps} KB/s"
            else -> "Mức: Không giới hạn"
        }

        if (item.isBlocked) {
            holder.btnBlock.text = "Mở chặn"
            holder.btnBlock.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_green))
            holder.btnBlock.setTextColor(Color.BLACK)
        } else {
            holder.btnBlock.text = "Chặn mạng"
            holder.btnBlock.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_red))
            holder.btnBlock.setTextColor(Color.WHITE)
        }

        holder.btnConfigure.setOnClickListener { onConfigureClick(item) }
        holder.btnBlock.setOnClickListener { onBlockClick(item) }
    }

    private fun formatSpeed(bps: Long): String {
        val kb = bps / 1024.0
        return if (kb >= 1024.0) {
            String.format(Locale.US, "%.2f MB/s", kb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f KB/s", kb)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }
}
