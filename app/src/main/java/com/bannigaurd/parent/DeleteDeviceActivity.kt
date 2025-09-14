    package com.bannigaurd.parent

    import android.os.Bundle
    import android.view.View
    import android.widget.ProgressBar
    import android.widget.TextView
    import android.widget.Toast
    import androidx.appcompat.app.AlertDialog
    import androidx.appcompat.app.AppCompatActivity
    import androidx.appcompat.widget.Toolbar
    import androidx.recyclerview.widget.LinearLayoutManager
    import androidx.recyclerview.widget.RecyclerView
    import com.google.firebase.auth.ktx.auth
    import com.google.firebase.database.DataSnapshot
    import com.google.firebase.database.DatabaseError
    import com.google.firebase.database.ValueEventListener
    import com.google.firebase.database.ktx.database
    import com.google.firebase.firestore.FieldValue
    import com.google.firebase.firestore.ktx.firestore
    import com.google.firebase.ktx.Firebase

    class DeleteDeviceActivity : AppCompatActivity() {

        private lateinit var recyclerView: RecyclerView
        private lateinit var progressBar: ProgressBar
        private lateinit var noDevicesTextView: TextView
        private lateinit var adapter: DeletableDeviceAdapter
        private val deviceList = mutableListOf<DeviceInfo>()

        private val auth = Firebase.auth
        private val firestore = Firebase.firestore
        private val rtdb = Firebase.database.reference

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_delete_device)

            val toolbar: Toolbar = findViewById(R.id.toolbar)
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            toolbar.setNavigationOnClickListener { finish() }

            recyclerView = findViewById(R.id.rvPairedDevices)
            progressBar = findViewById(R.id.progressBar)
            noDevicesTextView = findViewById(R.id.tvNoDevices)

            setupRecyclerView()
            fetchPairedDevices()
        }

        private fun setupRecyclerView() {
            adapter = DeletableDeviceAdapter(deviceList) { device ->
                showDeleteConfirmationDialog(device)
            }
            recyclerView.layoutManager = LinearLayoutManager(this)
            recyclerView.adapter = adapter
        }

        private fun fetchPairedDevices() {
            progressBar.visibility = View.VISIBLE
            val parentUid = auth.currentUser?.uid ?: return

            firestore.collection("users").document(parentUid).get().addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val deviceIds = doc.get("linkedDevices") as? List<String>
                    if (deviceIds.isNullOrEmpty()) {
                        handleEmptyOrError("No paired devices found.")
                    } else {
                        loadDeviceDetails(deviceIds)
                    }
                } else {
                    handleEmptyOrError("User profile not found.")
                }
            }.addOnFailureListener {
                handleEmptyOrError("Failed to fetch devices.")
            }
        }

        private fun loadDeviceDetails(deviceIds: List<String>) {
            deviceList.clear()
            val total = deviceIds.size
            var loaded = 0

            deviceIds.forEach { deviceId ->
                rtdb.child("devices").child(deviceId).addListenerForSingleValueEvent(object: ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val name = snapshot.child("deviceName").getValue(String::class.java) ?: "Unknown Device"
                        val status = snapshot.child("status").getValue(String::class.java) ?: "offline"
                        deviceList.add(DeviceInfo(deviceId, name, status == "online"))

                        loaded++
                        if (loaded == total) {
                            progressBar.visibility = View.GONE
                            adapter.notifyDataSetChanged()
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        loaded++
                        if (loaded == total) {
                            progressBar.visibility = View.GONE
                        }
                    }
                })
            }
        }

        private fun showDeleteConfirmationDialog(device: DeviceInfo) {
            AlertDialog.Builder(this)
                .setTitle("Delete Device")
                .setMessage("Are you sure you want to permanently delete '${device.name}'? All its data will be erased.")
                .setPositiveButton("Delete") { dialog, _ ->
                    deleteDevice(device)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun deleteDevice(device: DeviceInfo) {
            val parentUid = auth.currentUser?.uid ?: return

            // Step 1: Remove device ID from Firestore
            firestore.collection("users").document(parentUid)
                .update("linkedDevices", FieldValue.arrayRemove(device.id))
                .addOnSuccessListener {
                    // Step 2: Delete all data from Realtime Database
                    val pathsToDelete = listOf("devices", "callLogs", "contacts", "sms", "gallery", "files", "notifications", "commands")
                    pathsToDelete.forEach { path ->
                        rtdb.child(path).child(device.id).removeValue()
                    }

                    // Step 3: Update UI
                    adapter.removeItem(device)
                    Toast.makeText(this, "'${device.name}' has been deleted.", Toast.LENGTH_SHORT).show()
                    if(adapter.itemCount == 0){
                        handleEmptyOrError("No paired devices found.")
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to delete device. Please try again.", Toast.LENGTH_SHORT).show()
                }
        }

        private fun handleEmptyOrError(message: String){
            progressBar.visibility = View.GONE
            recyclerView.visibility = View.GONE
            noDevicesTextView.visibility = View.VISIBLE
            noDevicesTextView.text = message
        }
    }