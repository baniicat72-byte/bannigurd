package com.bannigaurd.bannikid

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.*
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import androidx.preference.PreferenceManager
import android.content.SharedPreferences
import androidx.core.content.edit
import com.bannigaurd.bannikid.AppConstants.ACTION_CAMERA_PERMISSION_GRANTED
import com.bannigaurd.bannikid.AppConstants.ACTION_MIC_PERMISSION_GRANTED
import com.bannigaurd.bannikid.AppConstants.ACTION_PERMISSION_GRANTED
import com.bannigaurd.bannikid.AppConstants.MEDIA_PROJECTION_DATA
import com.bannigaurd.bannikid.AppConstants.MEDIA_PROJECTION_RESULT_CODE
import com.bannigaurd.bannikid.AppConstants.REQUEST_CAMERA_PERMISSION_ACTION
import com.bannigaurd.bannikid.AppConstants.REQUEST_MIC_PERMISSION_ACTION
import com.bannigaurd.bannikid.AppConstants.START_SCREEN_SHARE_ACTION
import com.bannigaurd.bannikid.AppConstants.START_AUDIO_ACTION
import com.bannigaurd.bannikid.AppConstants.START_CAMERA_ACTION
import kotlinx.coroutines.launch

class PermissionRequestActivity : AppCompatActivity() {

    private val TAG = "PermissionRequestActivity"

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d(TAG, "Screen capture result: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
        val serviceIntent = Intent(this, RealtimeService::class.java).apply {
            action = START_SCREEN_SHARE_ACTION
            putExtra(MEDIA_PROJECTION_RESULT_CODE, result.resultCode)
            putExtra(MEDIA_PROJECTION_DATA, result.data)
            putExtra("DEVICE_ID", intent.getStringExtra("DEVICE_ID"))
            putExtra("ABLY_API_KEY", intent.getStringExtra("ABLY_API_KEY"))
        }.apply {
            putExtra(MEDIA_PROJECTION_RESULT_CODE, result.resultCode)
            putExtra(MEDIA_PROJECTION_DATA, result.data)
        }
            Log.d(TAG, "Starting RealtimeService for screen share after permission grant")
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            Log.w(TAG, "Screen capture permission denied/cancelled")
        }
        finish()
    }

    private val permissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val flashlightGranted = permissions["android.permission.FLASHLIGHT"] ?: false
        val foregroundServiceMicGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.FOREGROUND_SERVICE_MICROPHONE] ?: false
        } else {
            true // Not required for older versions
        }
        val foregroundServiceDataGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC] ?: false
        } else {
            true // Not required for older versions
        }
        val wakeLockGranted = permissions[Manifest.permission.WAKE_LOCK] ?: false
        val systemAlertGranted = permissions[Manifest.permission.SYSTEM_ALERT_WINDOW] ?: false

        Log.d(TAG, "🎯 Permission results:")
        Log.d(TAG, "   - CAMERA: $cameraGranted")
        Log.d(TAG, "   - RECORD_AUDIO: $audioGranted")
        Log.d(TAG, "   - FLASHLIGHT: $flashlightGranted")
        Log.d(TAG, "   - FGS_MICROPHONE: $foregroundServiceMicGranted")
        Log.d(TAG, "   - FGS_DATA_SYNC: $foregroundServiceDataGranted")
        Log.d(TAG, "   - WAKE_LOCK: $wakeLockGranted")
        Log.d(TAG, "   - SYSTEM_ALERT_WINDOW: $systemAlertGranted")

        val deviceId = intent.getStringExtra("DEVICE_ID")
        val ablyApiKey = intent.getStringExtra("ABLY_API_KEY")
        val requestedAction = intent.getStringExtra("REQUESTED_ACTION")

        // 🚀 HYBRID COMMUNICATION SYSTEM - Send via ALL 4 channels for Android 13+ compatibility

        if (cameraGranted) {
            Log.d(TAG, "📷 CAMERA permission granted - sending via all channels")
            sendPermissionViaAllChannels("CAMERA", deviceId, ablyApiKey, requestedAction)
        }

        if (audioGranted) {
            Log.d(TAG, "🎤 MIC permission granted - sending via all channels")
            sendPermissionViaAllChannels("MIC", deviceId, ablyApiKey, requestedAction)
        }

        // Direct service start as ultimate fallback
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (requestedAction != null) {
            val shouldStart = when (requestedAction) {
                START_AUDIO_ACTION -> micOk
                START_CAMERA_ACTION -> cameraOk && micOk
                else -> false
            }

            if (shouldStart) {
                Log.d(TAG, "🚀 Direct service start as fallback")
                val serviceIntent = Intent(this, RealtimeService::class.java).apply {
                    action = requestedAction
                }
                serviceIntent.putExtra("DEVICE_ID", deviceId)
                serviceIntent.putExtra("ABLY_API_KEY", ablyApiKey)
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }

        // Finish after delay to allow all communication channels to process
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "✅ PermissionRequestActivity finishing")
            finish()
        }, 1000)
    }

    /**
     * HYBRID COMMUNICATION SYSTEM - Sends permission grant via ALL 4 channels
     */
    private fun sendPermissionViaAllChannels(permissionType: String, deviceId: String?, ablyApiKey: String?, requestedAction: String?) {
        val permissionKey = when (permissionType) {
            "CAMERA" -> "camera_permission_granted"
            "MIC" -> "mic_permission_granted"
            else -> return
        }

        var action = when (permissionType) {
            "CAMERA" -> ACTION_CAMERA_PERMISSION_GRANTED
            "MIC" -> ACTION_MIC_PERMISSION_GRANTED
            else -> return
        }

        Log.d(TAG, "🔄 Sending $permissionType permission via ALL channels")

        // 📡 CHANNEL 1: LocalBroadcastManager (Primary for Android 13+)
        try {
            val localIntent = Intent(action).apply {
                putExtra("DEVICE_ID", deviceId)
                putExtra("ABLY_API_KEY", ablyApiKey)
                putExtra("REQUESTED_ACTION", requestedAction)
                putExtra("PERMISSION_TYPE", permissionType)
                putExtra("TIMESTAMP", System.currentTimeMillis())
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent)
            Log.d(TAG, "✅ LocalBroadcastManager: $permissionType permission sent")
        } catch (e: Exception) {
            Log.e(TAG, "❌ LocalBroadcastManager failed: ${e.message}")
        }

        // 💾 CHANNEL 2: SharedPreferences + LiveData (Backup)
        try {
            lifecycleScope.launch {
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this@PermissionRequestActivity)
                sharedPrefs.edit()
                    .putBoolean(permissionKey, true)
                    .putString("${permissionKey}_device_id", deviceId)
                    .putString("${permissionKey}_api_key", ablyApiKey)
                    .putString("${permissionKey}_action", requestedAction)
                    .putLong("${permissionKey}_timestamp", System.currentTimeMillis())
                    .apply()
                Log.d(TAG, "✅ SharedPreferences: $permissionType permission stored")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ SharedPreferences failed: ${e.message}")
        }

        // 🔗 CHANNEL 3: Direct Service Binding (Reliable)
        try {
            val serviceIntent = Intent(this, RealtimeService::class.java).apply {
                action = "PERMISSION_GRANTED"
                putExtra("PERMISSION_TYPE", permissionType)
                putExtra("DEVICE_ID", deviceId)
                putExtra("ABLY_API_KEY", ablyApiKey)
                putExtra("REQUESTED_ACTION", requestedAction)
            }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    Log.d(TAG, "✅ Service binding: Connected to RealtimeService")
                    try {
                        val messenger = Messenger(service)
                        val msg = Message.obtain().apply {
                            what = when (permissionType) {
                                "CAMERA" -> 1
                                "MIC" -> 2
                                else -> 0
                            }
                            data = Bundle().apply {
                                putString("DEVICE_ID", deviceId)
                                putString("ABLY_API_KEY", ablyApiKey)
                                putString("REQUESTED_ACTION", requestedAction)
                            }
                        }
                        messenger.send(msg)
                        Log.d(TAG, "✅ Service binding: Permission message sent")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Service binding message failed: ${e.message}")
                    }
                    unbindService(this)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.d(TAG, "ℹ️ Service binding: Disconnected from RealtimeService")
                }
            }

            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "✅ Service binding: Attempting to bind to RealtimeService")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Service binding failed: ${e.message}")
        }

        // ⚙️ CHANNEL 4: WorkManager (Ultimate Fallback)
        try {
            val workData = workDataOf(
                "PERMISSION_TYPE" to permissionType,
                "DEVICE_ID" to deviceId,
                "ABLY_API_KEY" to ablyApiKey,
                "REQUESTED_ACTION" to requestedAction,
                "TIMESTAMP" to System.currentTimeMillis()
            )

            val workRequest = OneTimeWorkRequestBuilder<PermissionWorker>()
                .setInputData(workData)
                .build()

            WorkManager.getInstance(this).enqueue(workRequest)
            Log.d(TAG, "✅ WorkManager: $permissionType permission work enqueued")
        } catch (e: Exception) {
            Log.e(TAG, "❌ WorkManager failed: ${e.message}")
        }

        Log.d(TAG, "🎉 $permissionType permission sent via ALL 4 channels successfully!")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set a simple layout for the permission activity
        setContentView(android.R.layout.simple_list_item_1)

        val requestedAction = intent?.getStringExtra("REQUESTED_ACTION")
        val message = when (requestedAction) {
            START_AUDIO_ACTION -> "🎤 Requesting microphone permission for live audio streaming..."
            START_CAMERA_ACTION -> "📷 Requesting camera and microphone permissions for live video streaming..."
            else -> "🔐 Requesting permissions..."
        }

        // Show message to user
        findViewById<android.widget.TextView>(android.R.id.text1)?.apply {
            text = message
            textSize = 18f
            setPadding(32, 32, 32, 32)
            gravity = android.view.Gravity.CENTER
        }

        Log.d(TAG, "onCreate: action=${intent?.action}, requested=$requestedAction")

        // FORCE VISIBILITY: Make activity visible over lock screen for permissions
        makeVisibleOverLock()

        if (isDeviceLocked()) {
            Log.w(TAG, "Device is locked. Requesting keyguard dismissal before permission flow")
            requestDismissKeyguardThen { handleIntent(intent) }
        } else {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: action=${intent.action}, requested=${intent.getStringExtra("REQUESTED_ACTION")}")
        if (isDeviceLocked()) {
            Log.w(TAG, "Device is locked onNewIntent. Requesting keyguard dismissal before permission flow")
            requestDismissKeyguardThen { handleIntent(intent) }
        } else {
            handleIntent(intent)
        }
    }

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    private fun requestDismissKeyguardThen(block: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) {
            block(); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    Log.d(TAG, "Keyguard dismissed successfully")
                    block()
                }
                override fun onDismissError() {
                    Log.w(TAG, "Keyguard dismiss error, proceeding anyway")
                    block()
                }
                override fun onDismissCancelled() {
                    Log.w(TAG, "Keyguard dismiss cancelled by user, proceeding anyway")
                    block()
                }
            })
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            block()
        }
    }

    private fun makeVisibleOverLock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun handleIntent(intent: Intent?) {
        val requestedAction = intent?.getStringExtra("REQUESTED_ACTION")
        Log.d(TAG, "handleIntent: action=${intent?.action}, requested=$requestedAction")
        when (intent?.action) {
            "com.bannigaurd.bannikid.REQUEST_SCREEN_CAPTURE" -> requestScreenCapture()
            REQUEST_CAMERA_PERMISSION_ACTION -> {
                // If this is for START_CAMERA, request both CAMERA + MIC together; otherwise just CAMERA
                if (requestedAction == START_CAMERA_ACTION) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA))
                }
            }
            REQUEST_MIC_PERMISSION_ACTION -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
            else -> {
                Log.w(TAG, "Unknown/empty action. Finishing.")
                finish()
            }
        }
    }

    private fun requestScreenCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun requestPermissions(permissions: Array<String>) {
        val allPermissions = mutableListOf<String>()

        // Add the requested permissions
        allPermissions.addAll(permissions)

        // Add Android 13+ specific permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!allPermissions.contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)) {
                allPermissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
            }
            if (!allPermissions.contains(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)) {
                allPermissions.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
            }
        }

        // Add additional permissions for camera functionality
        if (permissions.contains(Manifest.permission.CAMERA)) {
            if (!allPermissions.contains("android.permission.FLASHLIGHT")) {
                allPermissions.add("android.permission.FLASHLIGHT")
            }
        }

        // Add system permissions
        if (!allPermissions.contains(Manifest.permission.WAKE_LOCK)) {
            allPermissions.add(Manifest.permission.WAKE_LOCK)
        }

        // Add system alert window permission (special handling needed)
        if (!allPermissions.contains(Manifest.permission.SYSTEM_ALERT_WINDOW)) {
            allPermissions.add(Manifest.permission.SYSTEM_ALERT_WINDOW)
        }

        Log.d(TAG, "Launching runtime permission request for: ${allPermissions.joinToString()}")
        permissionRequestLauncher.launch(allPermissions.toTypedArray())
    }
}
