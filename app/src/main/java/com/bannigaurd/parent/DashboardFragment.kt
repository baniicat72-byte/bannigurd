package com.bannigaurd.parent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bannigaurd.parent.databinding.FragmentDashboardBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference

    private var deviceListener: ValueEventListener? = null
    private var wallpaperListener: ValueEventListener? = null

    private var allDeviceIds = listOf<String>()
    private val linkedDevicesList = mutableListOf<DeviceInfo>()
    private var activeChildDeviceId: String? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            result.data?.data?.let { uri ->
                uploadWallpaper(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.contentLayout.visibility = View.INVISIBLE

        binding.btnConnectDevice.setOnClickListener {
            startActivity(Intent(requireContext(), LinkDeviceActivity::class.java))
        }

        binding.btnSwitchDevice.setOnClickListener {
            if (allDeviceIds.isNotEmpty()) {
                showDeviceListPopup()
            } else {
                Toast.makeText(context, "No devices linked to switch.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.ivWallpaper.setOnClickListener {
            if (activeChildDeviceId != null) {
                val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
                pickImageLauncher.launch(intent)
            } else {
                Toast.makeText(context, "Please link a device first.", Toast.LENGTH_SHORT).show()
            }
        }

        fetchLinkedDevices()
    }

    private fun fetchLinkedDevices() {
        val parentUid = auth.currentUser?.uid ?: return

        firestore.collection("users").document(parentUid).get()
            .addOnSuccessListener { document ->
                if (!isAdded) return@addOnSuccessListener
                val devices = document.get("linkedDevices") as? List<String>
                if (!devices.isNullOrEmpty()) {
                    allDeviceIds = devices
                    val lastActiveDeviceId = getActiveDeviceId()
                    activeChildDeviceId = if (lastActiveDeviceId != null && allDeviceIds.contains(lastActiveDeviceId)) {
                        lastActiveDeviceId
                    } else {
                        allDeviceIds[0]
                    }
                    setActiveDeviceId(activeChildDeviceId!!)
                    attachDeviceListener(activeChildDeviceId!!)
                } else {
                    binding.contentLayout.visibility = View.VISIBLE // Show the connect button
                    Log.w("DashboardFragment", "User has no linked devices.")
                }
            }
    }

    private fun attachDeviceListener(deviceId: String) {
        if (deviceListener != null && activeChildDeviceId != null) {
            rtdb.child("devices").child(activeChildDeviceId!!).removeEventListener(deviceListener!!)
        }
        if (wallpaperListener != null && activeChildDeviceId != null) {
            rtdb.child("devices").child(activeChildDeviceId!!).child("wallpaperUrl").removeEventListener(wallpaperListener!!)
        }

        activeChildDeviceId = deviceId
        val deviceRef = rtdb.child("devices").child(deviceId)

        deviceListener = object: ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded || !snapshot.exists()) return

                binding.contentLayout.visibility = View.VISIBLE

                val deviceName = snapshot.child("deviceName").getValue(String::class.java) ?: "Kid's Device"
                val battery = snapshot.child("battery").getValue(Long::class.java)?.toInt() ?: -1
                val status = snapshot.child("status").getValue(String::class.java) ?: "offline"

                binding.tvDeviceName.text = deviceName
                binding.tvBatteryStatus.text = if (battery == -1) "N/A" else "$battery%"
                updateBatteryIcon(battery, binding.ivBattery)

                if (status == "online") {
                    binding.tvStatusText.text = "Online"
                    binding.tvStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_green))
                    binding.ivStatusDot.setImageResource(R.drawable.status_dot_green)
                } else {
                    binding.tvStatusText.text = "Offline"
                    binding.tvStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))
                    binding.ivStatusDot.setImageResource(R.drawable.status_dot_red)
                }
                // ... rest of UI updates
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        deviceRef.addValueEventListener(deviceListener!!)

        val wallpaperRef = deviceRef.child("wallpaperUrl")
        wallpaperListener = object: ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                val wallpaperUrl = snapshot.getValue(String::class.java)
                if (wallpaperUrl != null) {
                    Glide.with(this@DashboardFragment)
                        .load(wallpaperUrl)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .into(binding.ivWallpaper)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        wallpaperRef.addValueEventListener(wallpaperListener!!)
    }

    private fun showDeviceListPopup() {
        // ... (function remains the same)
    }

    private fun fetchDevicesForPopup(recyclerView: RecyclerView, dialog: BottomSheetDialog) {
        // ... (function remains the same)
    }

    private fun uploadWallpaper(uri: Uri) {
        // ... (function remains the same)
    }

    private fun updateBatteryIcon(level: Int, iconView: ImageView) {
        // ... (function remains the same)
    }

    private fun getActiveDeviceId(): String? {
        val prefs = activity?.getSharedPreferences("BANNIGUARD_PREFS", Context.MODE_PRIVATE)
        return prefs?.getString("ACTIVE_DEVICE_ID", null)
    }

    private fun setActiveDeviceId(deviceId: String) {
        val prefs = activity?.getSharedPreferences("BANNIGUARD_PREFS", Context.MODE_PRIVATE)?.edit()
        prefs?.putString("ACTIVE_DEVICE_ID", deviceId)
        prefs?.apply()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // ... (function remains the same)
        _binding = null
    }
}