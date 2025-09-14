package com.bannigaurd.parent

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bannigaurd.parent.databinding.ActivityDeviceChatBinding
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class DeviceChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceChatBinding
    private var childDeviceId: String? = null
    private lateinit var chatRef: DatabaseReference
    private var chatListener: ValueEventListener? = null
    private val messages = mutableListOf<DeviceChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupRecyclerView()
        fetchLinkedDevice()

        binding.btnSendChat.setOnClickListener {
            sendMessage()
        }
    }

    private fun setupRecyclerView() {
        binding.rvDeviceChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvDeviceChat.adapter = DeviceChatAdapter(messages)
    }

    private fun fetchLinkedDevice() {
        val parentUid = Firebase.auth.currentUser?.uid ?: return
        Firebase.firestore.collection("users").document(parentUid).get().addOnSuccessListener { doc ->
            childDeviceId = (doc.get("linkedDevices") as? List<String>)?.firstOrNull()
            if (childDeviceId != null) {
                attachChatListener()
            } else {
                Toast.makeText(this, "No linked device found.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun attachChatListener() {
        chatRef = Firebase.database.reference.child("deviceChat").child(childDeviceId!!)
        chatListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                messages.clear()
                snapshot.children.forEach {
                    it.getValue(DeviceChatMessage::class.java)?.let { msg ->
                        messages.add(msg)
                    }
                }
                binding.rvDeviceChat.adapter?.notifyDataSetChanged()
                binding.rvDeviceChat.scrollToPosition(messages.size - 1)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(baseContext, "Failed to load chat.", Toast.LENGTH_SHORT).show()
            }
        }
        chatRef.addValueEventListener(chatListener!!)
    }

    private fun sendMessage() {
        val messageText = binding.etChatMessage.text.toString().trim()
        if (messageText.isEmpty()) return

        val message = DeviceChatMessage(
            message = messageText,
            timestamp = System.currentTimeMillis(),
            sentBy = "PARENT"
        )
        chatRef.push().setValue(message)
        binding.etChatMessage.text.clear()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (chatListener != null && ::chatRef.isInitialized) {
            chatRef.removeEventListener(chatListener!!)
        }
    }
}