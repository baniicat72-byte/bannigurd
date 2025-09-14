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
import java.util.Locale

class AppsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var noAppsTextView: TextView

    private var childDeviceId: String? = null
    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference

    private var appsListener: ValueEventListener? = null
    private lateinit var appsRef: DatabaseReference

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_apps, container, false)
        recyclerView = view.findViewById(R.id.rvApps)
        progressBar = view.findViewById(R.id.progressBar)
        noAppsTextView = view.findViewById(R.id.tvNoApps)
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

        firestore.collection("users").document(parentUid).get().addOnSuccessListener { doc ->
            if (!isAdded) return@addOnSuccessListener
            val devices = doc.get("linkedDevices") as? List<String>
            if (!devices.isNullOrEmpty()) {
                childDeviceId = (activity?.application as? MyApp)?.childDeviceId ?: devices[0]
                fetchAppsList()
            } else {
                handleError("No linked device found.")
            }
        }.addOnFailureListener {
            if (isAdded) handleError("Could not fetch device info.")
        }
    }

    private fun fetchAppsList() {
        childDeviceId ?: return
        appsRef = rtdb.child("apps").child(childDeviceId!!)

        appsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return

                // सबसे पहले जांचें कि डेटा मौजूद है या नहीं
                if (!snapshot.exists()) {
                    handleError("No apps data found for this device yet.")
                    return
                }

                progressBar.visibility = View.GONE
                val appsList = mutableListOf<InstalledApp>()
                snapshot.children.forEach {
                    it.getValue(InstalledApp::class.java)?.let { app ->
                        appsList.add(app)
                    }
                }

                if (appsList.isEmpty()) {
                    handleError("No apps data found for this device yet.")
                } else {
                    noAppsTextView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE

                    appsList.sortWith(compareByDescending<InstalledApp> { it.isForeground }
                        .thenBy { it.appName?.lowercase(Locale.getDefault()) })

                    // सुनिश्चित करें कि एडॉप्टर पहले से मौजूद है या नहीं
                    if (recyclerView.adapter == null) {
                        recyclerView.adapter = AppsAdapter(requireContext(), appsList)
                    } else {
                        // यदि आप चाहते हैं कि लिस्ट अपडेट हो, तो आपको एडॉप्टर में एक अपडेट मेथड बनाना होगा
                        (recyclerView.adapter as AppsAdapter).updateData(appsList)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (isAdded) handleError("Failed to load apps list.")
            }
        }
        appsRef.addValueEventListener(appsListener!!)
    }

    private fun handleError(message: String) {
        if (isAdded) {
            progressBar.visibility = View.GONE
            recyclerView.visibility = View.GONE
            noAppsTextView.text = message
            noAppsTextView.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::appsRef.isInitialized && appsListener != null) {
            appsRef.removeEventListener(appsListener!!)
        }
    }
}