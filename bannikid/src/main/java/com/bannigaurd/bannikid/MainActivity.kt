package com.bannigaurd.bannikid

import android.app.AppOpsManager
import android.util.Log
import com.bannigaurd.bannikid.R
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bannigaurd.bannikid.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionsAdapter: PermissionsAdapter
    private val permissionItems = mutableListOf<PermissionItem>()

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // onResume status update ko handle karega
        }

    private val requestSpecialPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // onResume status update ko handle karega
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        val prefs = getSharedPreferences("BANNIKID_PREFS", Context.MODE_PRIVATE)
        val isDeviceLinked = prefs.getBoolean("IS_DEVICE_LINKED", false)

        // Check if launched from background service for permission request
        val fromBackgroundService = intent.getBooleanExtra("FROM_BACKGROUND_SERVICE", false)
        val requestedAction = intent.getStringExtra("REQUESTED_ACTION")

        if (isDeviceLinked && !fromBackgroundService) {
            val serviceIntent = Intent(this, MyDeviceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish()
            return
        }

        // If launched from background service, handle permission request directly
        if (fromBackgroundService && requestedAction != null) {
            Log.d("MainActivity", "🚀 Launched from background service for: $requestedAction")
            handleBackgroundPermissionRequest(requestedAction)
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupPermissionsList()
        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        if (this::binding.isInitialized) {
            checkPermissionsStatus()
        }
    }

    private fun setupPermissionsList() {
        permissionItems.clear()

        // ✅ FIX: Autostart और Battery Optimization को लिस्ट में जोड़ा गया है
        permissionItems.add(PermissionItem("AUTOSTART", "Autostart", "Required to run after phone restarts", ContextCompat.getDrawable(this, R.drawable.ic_usage)!!, PermissionType.MANUFACTURER_SPECIFIC))
        permissionItems.add(PermissionItem("BATTERY_OPTIMIZATION", "No Battery Restrictions", "Required to run in the background continuously", ContextCompat.getDrawable(this, R.drawable.ic_battery)!!, PermissionType.MANUFACTURER_SPECIFIC))

        permissionItems.add(PermissionItem("DEVICE_ADMIN", "Device Administrator", "Required to remotely lock the device", ContextCompat.getDrawable(this, R.drawable.ic_lock)!!, PermissionType.DEVICE_ADMIN))
        permissionItems.add(PermissionItem("ALL_FILES", "All Files Access", "Needed for wallpaper and file access", ContextCompat.getDrawable(this, R.drawable.ic_files)!!, PermissionType.ALL_FILES_ACCESS))
        permissionItems.add(PermissionItem("USAGE_STATS", "Usage Stats", "Allows monitoring of app usage time", ContextCompat.getDrawable(this, R.drawable.ic_usage)!!, PermissionType.USAGE_STATS))
        permissionItems.add(PermissionItem("ACCESSIBILITY", "Accessibility", "Required for advanced monitoring features", ContextCompat.getDrawable(this, R.drawable.ic_utilities)!!, PermissionType.ACCESSIBILITY))
        permissionItems.add(PermissionItem("OVERLAY", "Display Over Other Apps", "Needed to show alerts over other apps", ContextCompat.getDrawable(this, R.drawable.ic_screen_share)!!, PermissionType.OVERLAY))
        permissionItems.add(PermissionItem("NOTIFICATION_ACCESS", "Notification Access", "To read incoming notifications from apps", ContextCompat.getDrawable(this, R.drawable.ic_notifications)!!, PermissionType.NOTIFICATION_LISTENER))

        val requiredPermissions = mutableListOf(
            PermissionItem("CALL_LOGS", "Call & Contacts", "To monitor call history", ContextCompat.getDrawable(this, R.drawable.ic_call_logs)!!, PermissionType.NORMAL, android.Manifest.permission.READ_CALL_LOG),
            PermissionItem("SMS", "SMS Messages", "To view text messages", ContextCompat.getDrawable(this, R.drawable.ic_messages)!!, PermissionType.NORMAL, android.Manifest.permission.READ_SMS),
            PermissionItem("LOCATION", "Location", "To track the device's location", ContextCompat.getDrawable(this, R.drawable.ic_location)!!, PermissionType.NORMAL, android.Manifest.permission.ACCESS_FINE_LOCATION),
            PermissionItem("CAMERA", "Camera", "For live camera streaming", ContextCompat.getDrawable(this, R.drawable.ic_live_camera)!!, PermissionType.NORMAL, android.Manifest.permission.CAMERA),
            PermissionItem("MICROPHONE", "Microphone", "For live audio and recordings", ContextCompat.getDrawable(this, R.drawable.ic_recordings)!!, PermissionType.NORMAL, android.Manifest.permission.RECORD_AUDIO)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(PermissionItem("NOTIFICATIONS", "Notifications", "To show important alerts", ContextCompat.getDrawable(this, R.drawable.ic_notifications)!!, PermissionType.NORMAL, android.Manifest.permission.POST_NOTIFICATIONS))
        }
        permissionItems.addAll(requiredPermissions)
    }

    private fun setupRecyclerView() {
        permissionsAdapter = PermissionsAdapter(permissionItems) { item ->
            when (item.permissionType) {
                PermissionType.NORMAL -> {
                    val permissionsToRequest = when (item.id) {
                        "CALL_LOGS" -> arrayOf(android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_PHONE_STATE)
                        "LOCATION" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } else {
                            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                        else -> item.permissionString?.let { arrayOf(it) }
                    }
                    permissionsToRequest?.let { requestPermissionLauncher.launch(it) }
                }
                PermissionType.USAGE_STATS -> requestSpecialPermissionLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                PermissionType.ACCESSIBILITY -> requestSpecialPermissionLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                PermissionType.OVERLAY -> requestSpecialPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                PermissionType.ALL_FILES_ACCESS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        requestSpecialPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    }
                }
                PermissionType.NOTIFICATION_LISTENER -> {
                    requestSpecialPermissionLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                PermissionType.DEVICE_ADMIN -> {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "This permission is required to allow the parent to remotely lock this device.")
                    }
                    requestSpecialPermissionLauncher.launch(intent)
                }
                // ✅ FIX: Manufacturer-specific settings को खोलने का लॉजिक
                PermissionType.MANUFACTURER_SPECIFIC -> {
                    val prefs = getSharedPreferences("BANNIKID_PREFS", Context.MODE_PRIVATE).edit()
                    prefs.putBoolean(item.id + "_REQUESTED", true).apply()
                    openManufacturerSpecificSettings(item.id)
                }
                PermissionType.APP_SETTINGS -> {}
            }
        }
        binding.rvPermissions.layoutManager = LinearLayoutManager(this)
        binding.rvPermissions.adapter = permissionsAdapter
    }

    private fun checkPermissionsStatus() {
        val prefs = getSharedPreferences("BANNIKID_PREFS", Context.MODE_PRIVATE)
        var allGranted = true
        permissionItems.forEach { item ->
            item.isGranted = when (item.permissionType) {
                PermissionType.NORMAL -> item.permissionString?.let { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED } ?: false
                PermissionType.USAGE_STATS -> isUsageStatsAllowed()
                PermissionType.ACCESSIBILITY -> isAccessibilityServiceEnabled()
                PermissionType.OVERLAY -> Settings.canDrawOverlays(this)
                PermissionType.ALL_FILES_ACCESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
                PermissionType.NOTIFICATION_LISTENER -> isNotificationServiceEnabled()
                PermissionType.DEVICE_ADMIN -> devicePolicyManager.isAdminActive(adminComponent)
                // ✅ FIX: हम यह मान लेंगे कि अगर यूजर ने एक बार सेटिंग खोल ली, तो उसने परमिशन दे दी होगी।
                PermissionType.MANUFACTURER_SPECIFIC -> prefs.getBoolean(item.id + "_REQUESTED", false)
                else -> false
            }
            if (!item.isGranted) {
                allGranted = false
            }
        }

        permissionsAdapter.notifyDataSetChanged()

        if (allGranted) {
            val serviceIntent = Intent(this, MyDeviceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            val intent = Intent(this, QrCodeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    // ✅ FIX: अलग-अलग फोन के लिए सही सेटिंग्स पेज खोलने वाला नया फंक्शन
    private fun openManufacturerSpecificSettings(type: String) {
        val intents = mutableListOf<Intent>()
        val packageName = packageName

        when (type) {
            "AUTOSTART" -> {
                // Xiaomi
                intents.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                // Huawei
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
                // Oppo
                intents.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
                // Vivo
                intents.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
            }
            "BATTERY_OPTIMIZATION" -> {
                // Standard Android
                intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                // Xiaomi
                intents.add(Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")).putExtra("package_name", packageName).putExtra("package_label", getString(R.string.app_name)))
                // Huawei
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")))
            }
        }

        // Fallback to app details if no specific intent works
        intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

        var launched = false
        for (intent in intents) {
            try {
                requestSpecialPermissionLauncher.launch(intent)
                launched = true
                break
            } catch (e: Exception) {
                // This intent didn't work, try the next one
            }
        }
        if(!launched){
            Toast.makeText(this, "Could not open settings automatically. Please find it manually.", Toast.LENGTH_LONG).show()
        }
    }


    private fun isUsageStatsAllowed(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceId = "$packageName/${MyAccessibilityService::class.java.canonicalName}"
        val settingValue = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return settingValue?.contains(serviceId) ?: false
    }

    private fun isNotificationServiceEnabled(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    /**
     * Handle permission request when launched from background service
     */
    private fun handleBackgroundPermissionRequest(requestedAction: String) {
        Log.d("MainActivity", "🎯 Handling background permission request for: $requestedAction")

        // Set up a simple UI for the permission request
        setContentView(android.R.layout.simple_list_item_1)

        val message = when (requestedAction) {
            AppConstants.START_AUDIO_ACTION -> "🎤 Parent is requesting microphone access for live audio streaming"
            AppConstants.START_CAMERA_ACTION -> "📷 Parent is requesting camera and microphone access for live video streaming"
            else -> "🔐 Parent is requesting device access"
        }

        findViewById<android.widget.TextView>(android.R.id.text1)?.apply {
            text = message
            textSize = 18f
            setPadding(32, 32, 32, 32)
            gravity = android.view.Gravity.CENTER
        }

        // Automatically request the required permissions
        val permissionsToRequest = when (requestedAction) {
            AppConstants.START_AUDIO_ACTION -> arrayOf(android.Manifest.permission.RECORD_AUDIO)
            AppConstants.START_CAMERA_ACTION -> arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
            else -> arrayOf(android.Manifest.permission.RECORD_AUDIO)
        }

        Log.d("MainActivity", "🚀 Requesting permissions: ${permissionsToRequest.joinToString()}")

        // Use the existing permission launcher
        requestPermissionLauncher.launch(permissionsToRequest)

        // Set up a handler to finish the activity after permission request
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d("MainActivity", "✅ Background permission request completed, finishing activity")
            finish()
        }, 3000) // Give 3 seconds for permission dialog to appear
    }
}
