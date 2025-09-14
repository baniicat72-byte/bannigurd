package com.bannigaurd.parent

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class ConversationsAdapter(
    private val context: Context,
    private val conversations: List<SmsConversation>
) : RecyclerView.Adapter<ConversationsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = conversations[position]
        holder.bind(conversation)
    }

    override fun getItemCount(): Int = conversations.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.tvContactName)
        private val lastMessageTextView: TextView = itemView.findViewById(R.id.tvLastMessage)
        private val timestampTextView: TextView = itemView.findViewById(R.id.tvTimestamp)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val conversation = conversations[position]
                    val intent = Intent(context, ChatActivity::class.java).apply {
                        putExtra("CONTACT_NAME", conversation.contactName)
                        putExtra("PHONE_NUMBER", conversation.address)
                    }
                    context.startActivity(intent)
                }
            }
        }

        fun bind(conversation: SmsConversation) {
            nameTextView.text = conversation.contactName
            lastMessageTextView.text = conversation.lastMessage
            conversation.timestamp?.let {
                val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                timestampTextView.text = sdf.format(Date(it))
            }
        }
    }
}