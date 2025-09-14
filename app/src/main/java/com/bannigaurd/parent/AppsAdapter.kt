package com.bannigaurd.parent

import android.content.Context
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AppsAdapter(
    private val context: Context,
    private var apps: List<InstalledApp> // इसे var बनाया गया है
) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val appName: TextView = itemView.findViewById(R.id.tvAppName)
        val usageTime: TextView = itemView.findViewById(R.id.tvUsageTime)
        val installDate: TextView = itemView.findViewById(R.id.tvInstallDate)
        val appStatus: TextView = itemView.findViewById(R.id.tvAppStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_app_info, parent, false)
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
                    .into(holder.appIcon)
            } catch (e: Exception) {
                holder.appIcon.setImageResource(R.mipmap.ic_launcher_round)
            }
        } else {
            holder.appIcon.setImageResource(R.mipmap.ic_launcher_round)
        }

        // Format and set Usage Time
        holder.usageTime.text = "Usage: ${formatDuration(app.totalTimeInForeground)}"

        // Format and set Install Date
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        holder.installDate.text = "Installed: ${sdf.format(Date(app.installTime))}"

        // Show status if app is in foreground
        holder.appStatus.visibility = if (app.isForeground) View.VISIBLE else View.GONE
    }

    override fun getItemCount(): Int = apps.size

    // यह नया मेथड जोड़ा गया है
    fun updateData(newApps: List<InstalledApp>) {
        this.apps = newApps
        notifyDataSetChanged()
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return if (hours > 0) {
            String.format("%dh %dm", hours, minutes)
        } else {
            String.format("%dm", minutes)
        }
    }
}