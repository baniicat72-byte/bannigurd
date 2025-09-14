package com.bannigaurd.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var noNotificationsTextView: TextView
    private var childDeviceId: String? = null
    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference
    private var listener: ValueEventListener? = null
    private lateinit var notificationsRef: DatabaseReference

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_notifications, container, false)
        recyclerView = view.findViewById(R.id.rvNotificationConversations)
        progressBar = view.findViewById(R.id.progressBar)
        noNotificationsTextView = view.findViewById(R.id.tvNoNotifications)
        recyclerView.layoutManager = LinearLayoutManager(context)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fetchLinkedDevice()
    }

    private fun fetchLinkedDevice() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        noNotificationsTextView.visibility = View.GONE

        val parentUid = auth.currentUser?.uid
        if (parentUid == null) {
            handleError("Please log in again.")
            return
        }

        firestore.collection("users").document(parentUid).get().addOnSuccessListener { doc ->
            if (!isAdded) return@addOnSuccessListener
            val devices = doc.get("linkedDevices") as? List<String>
            if (!devices.isNullOrEmpty()) {
                childDeviceId = devices[0]
                (activity?.application as? MyApp)?.childDeviceId = childDeviceId
                attachNotificationListener()
            } else {
                handleError("No linked device found.")
            }
        }.addOnFailureListener {
            if (isAdded) handleError("Error finding linked device.")
        }
    }

    private fun attachNotificationListener() {
        childDeviceId ?: return
        notificationsRef = rtdb.child("notifications").child(childDeviceId!!)

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                progressBar.visibility = View.GONE

                val allNotifications = mutableListOf<NotificationItem>()
                snapshot.children.forEach { childSnapshot ->
                    childSnapshot.getValue(NotificationItem::class.java)?.let { allNotifications.add(it) }
                }
                processNotifications(allNotifications)
            }

            override fun onCancelled(error: DatabaseError) {
                if (isAdded) handleError("Failed to load notifications.")
            }
        }
        notificationsRef.addValueEventListener(listener!!)
    }

    private fun processNotifications(notifications: List<NotificationItem>) {
        if (notifications.isEmpty()) {
            handleError("No new notifications from the kid's device yet.")
            return
        }

        GlobalScope.launch(Dispatchers.Default) {
            val conversationsMap = mutableMapOf<String, NotificationConversation>()
            notifications.sortedBy { it.timestamp }.forEach { notif ->
                val conversationId = "${notif.packageName}_${notif.title}"
                val existing = conversationsMap[conversationId]
                if (existing != null) {
                    existing.lastMessage = notif.text ?: ""
                    existing.timestamp = notif.timestamp
                    existing.messageCount++
                } else {
                    conversationsMap[conversationId] = NotificationConversation(
                        conversationId = conversationId,
                        appName = notif.appName ?: "Unknown App",
                        packageName = notif.packageName ?: "",
                        senderName = notif.title ?: "Unknown Sender",
                        lastMessage = notif.text ?: "",
                        timestamp = notif.timestamp,
                        messageCount = 1,
                        appIconBase64 = notif.appIconBase64
                    )
                }
            }
            val conversationList = conversationsMap.values.sortedByDescending { it.timestamp }
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    recyclerView.adapter = NotificationConversationAdapter(requireContext(), conversationList)
                    recyclerView.visibility = View.VISIBLE
                    noNotificationsTextView.visibility = View.GONE
                }
            }
        }
    }

    private fun handleError(message: String) {
        if(isAdded) {
            progressBar.visibility = View.GONE
            noNotificationsTextView.text = message
            noNotificationsTextView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::notificationsRef.isInitialized && listener != null) {
            notificationsRef.removeEventListener(listener!!)
        }
    }
}