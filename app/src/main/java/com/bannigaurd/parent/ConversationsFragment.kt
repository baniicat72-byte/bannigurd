package com.bannigaurd.parent

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class ConversationsFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private var childDeviceId: String? = null
    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference
    private lateinit var smsRef: DatabaseReference
    private lateinit var valueEventListener: ValueEventListener

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_conversations, container, false)
        recyclerView = view.findViewById(R.id.rvConversations)
        recyclerView.layoutManager = LinearLayoutManager(context)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fetchLinkedDevice()
    }

    private fun fetchLinkedDevice() {
        val parentUid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(parentUid).get().addOnSuccessListener { doc ->
            val devices = doc.get("linkedDevices") as? List<String>
            if (!devices.isNullOrEmpty()) {
                childDeviceId = devices[0]
                attachSmsListener()
            }
        }
    }

    private fun attachSmsListener() {
        childDeviceId ?: return
        smsRef = rtdb.child("sms").child(childDeviceId!!)

        valueEventListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                val allMessages = mutableListOf<SmsMessage>()
                snapshot.children.forEach {
                    it.getValue(SmsMessage::class.java)?.let { msg -> allMessages.add(msg) }
                }
                processMessages(allMessages)
            }
            override fun onCancelled(error: DatabaseError) {
                if(isAdded) Toast.makeText(context, "Failed to load messages: ${error.message}", Toast.LENGTH_LONG).show()
                Log.e("ConversationsFragment", "Firebase Error: ${error.message}")
            }
        }
        smsRef.addValueEventListener(valueEventListener)
    }

    private fun processMessages(messages: List<SmsMessage>) {
        if (!isAdded || messages.isEmpty()) {
            // Handle empty case if needed
            return
        }
        val groupedByAddress = messages.groupBy { it.address }
        val conversations = groupedByAddress.mapNotNull { (address, messageList) ->
            if (address == null) return@mapNotNull null
            val latestMessage = messageList.maxByOrNull { it.date ?: 0 }
            SmsConversation(
                address = address,
                contactName = latestMessage?.address ?: "Unknown",
                lastMessage = latestMessage?.body,
                timestamp = latestMessage?.date
            )
        }.sortedByDescending { it.timestamp ?: 0 }

        if (view != null) {
            recyclerView.adapter = ConversationsAdapter(requireContext(), conversations)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::smsRef.isInitialized && ::valueEventListener.isInitialized) {
            smsRef.removeEventListener(valueEventListener)
        }
    }
}