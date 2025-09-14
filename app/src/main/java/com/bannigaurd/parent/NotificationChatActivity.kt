package com.bannigaurd.parent

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class NotificationChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private var childDeviceId: String? = null
    private var conversationId: String? = null
    private lateinit var notificationsRef: DatabaseReference
    private var listener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_chat)

        conversationId = intent.getStringExtra("CONVERSATION_ID")
        val senderName = intent.getStringExtra("SENDER_NAME")
        childDeviceId = (application as? MyApp)?.childDeviceId

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = senderName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.rvChat)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        recyclerView.layoutManager = layoutManager

        if (conversationId != null && childDeviceId != null) {
            attachListener()
        } else {
            Toast.makeText(this, "Error: Missing device or conversation info.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun attachListener() {
        notificationsRef = Firebase.database.reference.child("notifications").child(childDeviceId!!)

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = mutableListOf<NotificationItem>()
                snapshot.children.forEach {
                    val msg = it.getValue(NotificationItem::class.java)
                    if (msg != null) {
                        val currentConvId = "${msg.packageName}_${msg.title}"
                        if (currentConvId == conversationId) {
                            messages.add(msg)
                        }
                    }
                }
                messages.sortBy { it.timestamp }
                recyclerView.adapter = NotificationChatAdapter(this@NotificationChatActivity, messages)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(baseContext, "Failed to load chat.", Toast.LENGTH_SHORT).show()
            }
        }
        notificationsRef.addValueEventListener(listener!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::notificationsRef.isInitialized && listener != null) {
            notificationsRef.removeEventListener(listener!!)
        }
    }
}