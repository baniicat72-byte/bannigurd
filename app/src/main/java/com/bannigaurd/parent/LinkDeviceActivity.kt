package com.bannigaurd.parent

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bannigaurd.parent.databinding.ActivityLinkDeviceBinding
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class LinkDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkDeviceBinding
    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference

    private val qrCodeScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val deviceId = result.contents
            findAndLinkDeviceById(deviceId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.backButton.setOnClickListener { finish() }
        binding.scanQrButton.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Scan QR code from Kid's device")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }
            qrCodeScannerLauncher.launch(options)
        }
        binding.connectButton.setOnClickListener {
            val pin = binding.pinEditText.text.toString().trim()
            if (pin.length == 6) {
                findAndLinkDeviceByPin(pin)
            } else {
                Toast.makeText(this, "Please enter a valid 6-digit PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun findAndLinkDeviceById(deviceId: String) {
        val deviceRef = rtdb.child("devices").child(deviceId)
        deviceRef.addListenerForSingleValueEvent(object: ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val oldParentId = snapshot.child("parentId").getValue(String::class.java)
                    linkDeviceToParent(deviceId, oldParentId)
                } else {
                    Toast.makeText(baseContext, "Invalid QR Code. Device not found.", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(baseContext, "Database error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun findAndLinkDeviceByPin(pin: String) {
        val query = rtdb.child("devices").orderByChild("pinCode").equalTo(pin)
        query.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val deviceSnapshot = snapshot.children.first()
                    val deviceId = deviceSnapshot.key
                    val oldParentId = deviceSnapshot.child("parentId").getValue(String::class.java)
                    if (deviceId != null) {
                        linkDeviceToParent(deviceId, oldParentId)
                    }
                } else {
                    Toast.makeText(baseContext, "Invalid PIN. No device found.", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(baseContext, "Database error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun linkDeviceToParent(deviceId: String, oldParentId: String?) {
        val newParentUid = auth.currentUser?.uid ?: return

        // If the device was linked to another parent, remove it from their list
        if (oldParentId != null && oldParentId != newParentUid) {
            firestore.collection("users").document(oldParentId)
                .update("linkedDevices", FieldValue.arrayRemove(deviceId))
        }

        // 1. Set the new parentId in the Realtime Database
        rtdb.child("devices").child(deviceId).child("parentId").setValue(newParentUid)
            .addOnSuccessListener {
                // 2. Add the deviceId to the new parent's profile in Firestore
                val newUserDocRef = firestore.collection("users").document(newParentUid)
                val data = mapOf("linkedDevices" to FieldValue.arrayUnion(deviceId))
                newUserDocRef.set(data, SetOptions.merge())
                    .addOnSuccessListener {
                        Toast.makeText(this, "Device linked successfully!", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, DashboardActivity::class.java))
                        finishAffinity()
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to link device: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}