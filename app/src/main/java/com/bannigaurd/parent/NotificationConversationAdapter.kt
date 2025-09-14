package com.bannigaurd.parent

import android.content.Context
import android.content.Intent
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

class NotificationConversationAdapter(
    private val context: Context,
    private val conversations: List<NotificationConversation>
) : RecyclerView.Adapter<NotificationConversationAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val senderName: TextView = itemView.findViewById(R.id.tvSenderName)
        val lastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
        val timestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_notification_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = conversations[position]
        holder.senderName.text = conversation.senderName
        holder.lastMessage.text = conversation.lastMessage
        holder.timestamp.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(conversation.timestamp))

        if (!conversation.appIconBase64.isNullOrEmpty()) {
            try {
                val imageBytes = Base64.decode(conversation.appIconBase64, Base64.DEFAULT)
                Glide.with(context)
                    .asBitmap()
                    .load(imageBytes)
                    .placeholder(R.mipmap.ic_launcher_round)
                    .error(R.mipmap.ic_launcher_round)
                    .into(holder.appIcon)
            } catch (e: Exception) {
                holder.appIcon.setImageResource(R.mipmap.ic_launcher_round)
            }
        } else {
            holder.appIcon.setImageResource(R.mipmap.ic_launcher_round)
        }

        holder.itemView.setOnClickListener {
            val intent = Intent(context, NotificationChatActivity::class.java).apply {
                putExtra("CONVERSATION_ID", conversation.conversationId)
                putExtra("SENDER_NAME", conversation.senderName)
            }
            context.startActivity(intent)
        }
    }

    override fun getItemCount() = conversations.size
}