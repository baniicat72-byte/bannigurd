package com.bannigaurd.parent

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class CallLogAdapter(
    private val context: Context,
    private var callGroups: List<CallGroup>,
    private val onCallClick: (String) -> Unit
) : RecyclerView.Adapter<CallLogAdapter.GroupViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_call_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(callGroups[position])
    }

    override fun getItemCount(): Int = callGroups.size

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.tv_caller_name)
        private val numberTextView: TextView = itemView.findViewById(R.id.tv_phone_number)
        private val callButton: ImageButton = itemView.findViewById(R.id.call_button)
        private val detailsContainer: LinearLayout = itemView.findViewById(R.id.details_container)

        init {
            itemView.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    val group = callGroups[bindingAdapterPosition]
                    group.isExpanded = !group.isExpanded
                    notifyItemChanged(bindingAdapterPosition)
                }
            }
        }

        fun bind(group: CallGroup) {
            nameTextView.text = group.displayName
            numberTextView.text = group.number

            // --- YEH LINE SUNISHCHIT KARTI HAI KI SAHI NUMBER JAAYE ---
            callButton.setOnClickListener { onCallClick(group.number) }

            detailsContainer.removeAllViews()

            if (group.isExpanded) {
                detailsContainer.visibility = View.VISIBLE
                group.calls.forEach { call ->
                    val detailView = LayoutInflater.from(context).inflate(R.layout.item_call_detail, detailsContainer, false)

                    val callTypeIcon = detailView.findViewById<ImageView>(R.id.iv_call_type)
                    val callTimeText = detailView.findViewById<TextView>(R.id.tv_call_time)
                    val callDurationText = detailView.findViewById<TextView>(R.id.tv_call_duration)

                    callTimeText.text = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()).format(Date(call.date ?: 0))

                    val duration = call.duration ?: 0
                    val minutes = TimeUnit.SECONDS.toMinutes(duration)
                    val seconds = duration - TimeUnit.MINUTES.toSeconds(minutes)
                    callDurationText.text = String.format("%dm %ds", minutes, seconds)

                    val iconRes = when (call.type) {
                        "Incoming" -> R.drawable.ic_incoming_call
                        "Outgoing" -> R.drawable.ic_call_outgoing
                        "Missed" -> R.drawable.ic_missed_call
                        else -> R.drawable.ic_call
                    }
                    callTypeIcon.setImageResource(iconRes)
                    detailsContainer.addView(detailView)
                }
            } else {
                detailsContainer.visibility = View.GONE
            }
        }
    }
}