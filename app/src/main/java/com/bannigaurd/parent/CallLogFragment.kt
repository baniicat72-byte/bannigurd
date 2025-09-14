package com.bannigaurd.parent

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class CallLogFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var noLogsTextView: TextView
    private lateinit var callLogAdapter: CallLogAdapter
    private val callLogList = mutableListOf<CallLog>()
    private var groupedCallList = mutableListOf<CallGroup>()
    private var childDeviceId: String? = null

    // --- YEH NAYA VARIABLE ADD KAREIN ---
    private var lastCommandTime: Long = 0

    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_call_logs, container, false)
        recyclerView = view.findViewById(R.id.rvCallLogs)
        progressBar = view.findViewById(R.id.progressBar)
        noLogsTextView = view.findViewById(R.id.tvNoLogs)
        recyclerView.layoutManager = LinearLayoutManager(context)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fetchLinkedDevice()
    }

    private fun fetchLinkedDevice() {
        progressBar.visibility = View.VISIBLE
        val parentUid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(parentUid).get()
            .addOnSuccessListener { document ->
                if (!isAdded) return@addOnSuccessListener
                val devices = document.get("linkedDevices") as? List<String>
                if (!devices.isNullOrEmpty()) {
                    childDeviceId = devices[0]
                    fetchCallLogs()
                } else {
                    progressBar.visibility = View.GONE
                    noLogsTextView.visibility = View.VISIBLE
                    noLogsTextView.text = "No linked device found."
                }
            }
            .addOnFailureListener {
                if (!isAdded) return@addOnFailureListener
                progressBar.visibility = View.GONE
                noLogsTextView.visibility = View.VISIBLE
                noLogsTextView.text = "Error finding device."
            }
    }

    private fun fetchCallLogs() {
        if (childDeviceId == null) {
            progressBar.visibility = View.GONE
            noLogsTextView.visibility = View.VISIBLE
            noLogsTextView.text = "Device ID is missing."
            return
        }

        val callLogRef = rtdb.child("callLogs").child(childDeviceId!!)
        callLogRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                callLogList.clear()
                snapshot.children.forEach {
                    val log = it.getValue(CallLog::class.java)
                    if (log != null) {
                        callLogList.add(log)
                    }
                }
                groupAndDisplayLogs()
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                progressBar.visibility = View.GONE
                Log.e("CallLogFragment", "Failed to read call logs", error.toException())
            }
        })
    }

    private fun groupAndDisplayLogs() {
        if (!isAdded) return
        progressBar.visibility = View.GONE

        if (callLogList.isEmpty()) {
            noLogsTextView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }

        recyclerView.visibility = View.VISIBLE
        noLogsTextView.visibility = View.GONE

        val groupedByNumber = callLogList.groupBy { it.number ?: "Unknown" }
        groupedCallList.clear()

        for ((number, logs) in groupedByNumber) {
            val mostRecentCall = logs.maxByOrNull { it.date ?: 0 }
            val displayName = mostRecentCall?.name?.takeIf { it.isNotBlank() && it != "Unknown" } ?: number

            groupedCallList.add(
                CallGroup(
                    identifier = number,
                    displayName = displayName,
                    number = number,
                    calls = logs.sortedByDescending { it.date ?: 0 }
                )
            )
        }

        groupedCallList.sortByDescending { it.calls.firstOrNull()?.date ?: 0 }

        callLogAdapter = CallLogAdapter(requireContext(), groupedCallList) { phoneNumber ->
            makeCall(phoneNumber)
        }
        recyclerView.adapter = callLogAdapter
    }

    // --- 👇 YEH FUNCTION UPDATE HUA HAI 👇 ---
    private fun makeCall(phoneNumber: String) {
        if (childDeviceId == null) {
            Toast.makeText(context, "Device ID not found.", Toast.LENGTH_SHORT).show()
            return
        }

        // Cooldown logic: 5 second ka gap
        if (System.currentTimeMillis() - lastCommandTime < 5000) {
            Toast.makeText(context, "Please wait before sending another command.", Toast.LENGTH_SHORT).show()
            return
        }
        lastCommandTime = System.currentTimeMillis()

        val commandRef = rtdb.child("commands").child(childDeviceId!!)
        commandRef.child("makeCall").setValue(phoneNumber)
            .addOnSuccessListener {
                Toast.makeText(context, "Sending call command...", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to send call command.", Toast.LENGTH_SHORT).show()
                // Agar fail ho to cooldown hata dein taki user dubara try kar sake
                lastCommandTime = 0
            }
    }
}