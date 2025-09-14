package com.bannigaurd.parent

import android.content.Context
import android.text.format.Formatter
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.concurrent.TimeUnit

class UsageAdapter(
    private val context: Context,
    private val apps: List<AppUsageInfo>,
    // --- YEH LAMBDA FUNCTION ADD KIYA GAYA HAI ---
    private val onBlockToggle: (app: AppUsageInfo, isBlocked: Boolean) -> Unit
) : RecyclerView.Adapter<UsageAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val appName: TextView = itemView.findViewById(R.id.tvAppName)
        val usageTime: TextView = itemView.findViewById(R.id.tvUsageTime)
        val lastUsed: TextView = itemView.findViewById(R.id.tvLastUsed)
        val dataUsage: TextView = itemView.findViewById(R.id.tvDataUsage)
        // --- YEH UI ELEMENTS ADD KIYE GAYE HAIN ---
        val timeLimit: TextView = itemView.findViewById(R.id.tvTimeLimit)
        val blockSwitch: SwitchMaterial = itemView.findViewById(R.id.switchBlockApp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_usage_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]

        holder.appName.text = app.appName

        // Set Icon from Base64 String
        if (!app.appIconBase64.isNullOrEmpty()) {
            try {
                val imageBytes = Base64.decode(app.appIconBase64, Base64.DEFAULT)
                Glide.with(context)
                    .asBitmap()
                    .load(imageBytes)
                    .placeholder(R.mipmap.ic_launcher_round)
                    .into(holder.appIcon)
            } catch (e: Exception) {
                holder.appIcon.setImageResource(R.mipmap.ic_launcher_round)
            }
        } else {
            holder.appIcon.setImageResource(R.mipmap.ic_launcher_round)
        }

        // Format and set Usage Time
        holder.usageTime.text = "Usage: ${formatDuration(app.usageTimeToday)}"

        // Format and set Data Usage
        holder.dataUsage.text = "Data: ${Formatter.formatShortFileSize(context, app.dataUsageToday)}"

        // Format and set Last Used Time
        holder.lastUsed.text = "Last used: ${formatLastUsed(app.lastTimeUsed, app.isForeground)}"

        // --- 👇 YEH NAYA LOGIC ADD KIYA GAYA HAI 👇 ---

        // Show Time Limit
        if (app.timeLimit > 0) {
            holder.timeLimit.text = "• Limit: ${app.timeLimit}m"
            holder.timeLimit.visibility = View.VISIBLE
        } else {
            holder.timeLimit.visibility = View.GONE
        }

        // Handle Block Switch
        holder.blockSwitch.setOnCheckedChangeListener(null) // Listener ko remove karein taki infinite loop na ho
        holder.blockSwitch.isChecked = app.isBlocked
        holder.blockSwitch.setOnCheckedChangeListener { _, isChecked ->
            onBlockToggle(app, isChecked)
        }

        // TODO: Add click listener to holder.itemView or holder.timeLimit to open a dialog for setting time limit
        // --- 👆 NAYA LOGIC YAHAN KHATAM HOTA HAI 👆 ---
    }

    override fun getItemCount(): Int = apps.size

    private fun formatDuration(millis: Long): String {
        if (millis < 60000) return "Less than 1m"
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return if (hours > 0) {
            String.format("%dh %dm", hours, minutes)
        } else {
            String.format("%dm", minutes)
        }
    }

    private fun formatLastUsed(timestamp: Long, isForeground: Boolean): String {
        if (isForeground) return "Open"
        if (timestamp == 0L) return "Never"
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)

        return when {
            minutes < 2 -> "Just now"
            minutes < 60 -> "$minutes m ago"
            hours < 24 -> "$hours h ago"
            else -> "$days d ago"
        }
    }
}