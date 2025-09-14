package com.bannigaurd.bannikid

import android.graphics.Color
import com.bannigaurd.bannikid.R
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class PermissionsAdapter(
    private val permissionItems: List<PermissionItem>,
    private val onGrantClicked: (PermissionItem) -> Unit
) : RecyclerView.Adapter<PermissionsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val name: TextView = view.findViewById(R.id.tvPermissionName)
        val description: TextView = view.findViewById(R.id.tvPermissionDesc)
        val grantButton: Button = view.findViewById(R.id.btnGrant)
        val statusIcon: ImageView = view.findViewById(R.id.ivStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_permission, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = permissionItems[position]
        holder.icon.setImageDrawable(item.icon)
        holder.name.text = item.name
        holder.description.text = item.description

        if (item.isGranted) {
            holder.grantButton.text = "Granted"
            holder.grantButton.isEnabled = false
            holder.grantButton.setBackgroundColor(Color.parseColor("#555555"))
            holder.grantButton.setTextColor(Color.parseColor("#4CAF50")) // Green text
            holder.statusIcon.setImageDrawable(ContextCompat.getDrawable(holder.itemView.context, R.drawable.ic_check))
        } else {
            holder.grantButton.text = "Grant"
            holder.grantButton.isEnabled = true
            holder.grantButton.setBackgroundColor(Color.parseColor("#555555"))
            holder.grantButton.setTextColor(Color.WHITE)
            holder.statusIcon.setImageDrawable(ContextCompat.getDrawable(holder.itemView.context, R.drawable.ic_cross))
        }

        holder.grantButton.setOnClickListener {
            if (!item.isGranted) {
                onGrantClicked(item)
            }
        }
    }

    override fun getItemCount() = permissionItems.size
}
