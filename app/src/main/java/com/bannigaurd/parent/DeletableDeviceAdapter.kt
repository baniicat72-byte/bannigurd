package com.bannigaurd.parent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeletableDeviceAdapter(
    private val devices: MutableList<DeviceInfo>,
    private val onDeleteClick: (DeviceInfo) -> Unit
) : RecyclerView.Adapter<DeletableDeviceAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val deviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        val deleteButton: ImageButton = itemView.findViewById(R.id.btnDeleteDevice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_deletable_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.deviceName.text = device.name
        holder.deleteButton.setOnClickListener {
            onDeleteClick(device)
        }
    }

    override fun getItemCount(): Int = devices.size

    fun removeItem(device: DeviceInfo) {
        val position = devices.indexOf(device)
        if (position > -1) {
            devices.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}