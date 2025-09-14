package com.bannigaurd.parent

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class ChatActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private var childDeviceId: String? = null
    private var phoneNumber: String? = null
    private lateinit var messagesRef: DatabaseReference
    private lateinit var valueEventListener: ValueEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        phoneNumber = intent.getStringExtra("PHONE_NUMBER")
        val contactName = intent.getStringExtra("CONTACT_NAME") ?: phoneNumber

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = contactName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.rvChatMessages)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        recyclerView.layoutManager = layoutManager

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        if (phoneNumber != null) {
            fetchLinkedDevice()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun fetchLinkedDevice() {
        val parentUid = Firebase.auth.currentUser?.uid ?: return
        Firebase.firestore.collection("users").document(parentUid).get().addOnSuccessListener { doc ->
            val devices = doc.get("linkedDevices") as? List<String>
            if (!devices.isNullOrEmpty()) {
                childDeviceId = devices[0]
                attachMessageListener()
            }
        }
    }

    private fun attachMessageListener() {
        if (childDeviceId == null || phoneNumber == null) return
        messagesRef = Firebase.database.reference.child("sms").child(childDeviceId!!)

        valueEventListener = object: ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = mutableListOf<SmsMessage>()
                snapshot.children.forEach {
                    val msg = it.getValue(SmsMessage::class.java)
                    if (msg != null && msg.address == phoneNumber) {
                        messages.add(msg)
                    }
                }
                messages.sortBy { it.date }
                recyclerView.adapter = ChatAdapter(this@ChatActivity, messages)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(baseContext, "Failed to load chat.", Toast.LENGTH_SHORT).show()
            }
        }
        messagesRef.addValueEventListener(valueEventListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::messagesRef.isInitialized && ::valueEventListener.isInitialized) {
            messagesRef.removeEventListener(valueEventListener)
        }
    }
}