    package com.bannigaurd.parent

    import android.content.Context
    import android.view.LayoutInflater
    import android.view.View
    import android.view.ViewGroup
    import android.widget.ImageView
    import android.widget.TextView
    import androidx.recyclerview.widget.RecyclerView

    class DeviceAdapter(
        private val context: Context,
        private val devices: List<DeviceInfo>,
        private val onDeviceClick: (DeviceInfo) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = devices[position]
            holder.bind(device)
        }

        override fun getItemCount(): Int = devices.size

        inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon: ImageView = itemView.findViewById(R.id.ivDeviceIcon)
            private val name: TextView = itemView.findViewById(R.id.tvDeviceNameItem)
            private val statusDot: ImageView = itemView.findViewById(R.id.ivStatusDotItem)

            fun bind(device: DeviceInfo) {
                name.text = device.name
                val statusDrawable = if (device.isOnline) R.drawable.status_dot_green else R.drawable.status_dot_red
                statusDot.setImageResource(statusDrawable)

                itemView.setOnClickListener {
                    onDeviceClick(device)
                }
            }
        }
    }