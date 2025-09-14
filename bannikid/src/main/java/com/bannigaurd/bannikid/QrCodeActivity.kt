package com.bannigaurd.bannikid

import android.annotation.SuppressLint
import com.bannigaurd.bannikid.R
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.zxing.BarcodeFormat // <-- यह नया import है
import com.journeyapps.barcodescanner.BarcodeEncoder

class QrCodeActivity : AppCompatActivity() {

    private lateinit var deviceId: String
    private lateinit var deviceNode: DatabaseReference
    private var linkListener: ValueEventListener? = null

    @SuppressLint("HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_code)

        val ivQrCode = findViewById<ImageView>(R.id.ivQrCode)
        val tvPinCode = findViewById<TextView>(R.id.tvPinCode)

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val pinCode = (deviceId.hashCode().toLong() and 0xFFFFFF % 900000 + 100000).toString()

        tvPinCode.text = "${pinCode.substring(0, 3)} ${pinCode.substring(3, 6)}"

        val batteryPct = getBatteryPercentage()
        val deviceData = mapOf(
            "pinCode" to pinCode,
            "status" to "pending",
            "timestamp" to System.currentTimeMillis(),
            "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "androidVersion" to Build.VERSION.RELEASE,
            "battery" to batteryPct,
            "lastUpdated" to System.currentTimeMillis()
        )

        deviceNode = Firebase.database.reference.child("devices").child(deviceId)
        deviceNode.updateChildren(deviceData).addOnSuccessListener {
             try {
                val barcodeEncoder = BarcodeEncoder()
                val bitmap: Bitmap = barcodeEncoder.encodeBitmap(deviceId, BarcodeFormat.QR_CODE, 400, 400)
                ivQrCode.setImageBitmap(bitmap)
                Toast.makeText(this, "Device is ready to be linked.", Toast.LENGTH_SHORT).show()
                listenForParentLink()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Database Error: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun listenForParentLink() {
        val parentIdRef = deviceNode.child("parentId")
        linkListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val parentId = snapshot.getValue(String::class.java)
                    if (!parentId.isNullOrEmpty()) {

                        val prefs = getSharedPreferences("BanniKidPrefs", Context.MODE_PRIVATE).edit()
                        prefs.putBoolean("IS_DEVICE_LINKED", true)
                        prefs.putString("DEVICE_ID", deviceId)
                        prefs.apply()

                        val serviceIntent = Intent(applicationContext, MyDeviceService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }

                        Toast.makeText(applicationContext, "Device linked successfully! App will run in background.", Toast.LENGTH_LONG).show()

                        parentIdRef.removeEventListener(this)

                        val intent = Intent(Intent.ACTION_MAIN)
                        intent.addCategory(Intent.CATEGORY_HOME)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        finishAffinity()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("QrCodeActivity", "Error listening for parent link: ${error.message}")
            }
        }
        parentIdRef.addValueEventListener(linkListener!!)
    }

    private fun getBatteryPercentage(): Int {
        val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = applicationContext.registerReceiver(null, iFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level == -1 || scale == -1) -1 else (level / scale.toFloat() * 100).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (linkListener != null) {
            if(::deviceNode.isInitialized) {
                deviceNode.child("parentId").removeEventListener(linkListener!!)
            }
        }
    }
}
