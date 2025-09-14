// bannikid/MyDeviceService.kt

package com.bannigaurd.bannikid

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import com.bannigaurd.bannikid.R
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bannigaurd.bannikid.managers.*
import com.bannigaurd.bannikid.util.DeviceUtil
import com.bannigaurd.bannikid.workers.CleanupRecordingsWorker
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.util.*
import java.util.concurrent.TimeUnit

class MyDeviceService : Service() {

    private val database = Firebase.database.reference
    private var dataSyncTimer: Timer? = null
    private lateinit var deviceId: String

    // Managers
    private lateinit var callLogManager: CallLogManager
    private lateinit var contactManager: ContactManager
    private lateinit var smsManager: SmsManager
    private lateinit var appUsageManager: AppUsageManager
    private lateinit var locationManager: LocationManager
    private lateinit var commandManager: CommandManager
    private lateinit var installedAppsManager: InstalledAppsManager

    // FIX: DevicePolicyManager का सही उपयोग किया गया है
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var ttsManager: TextToSpeechManager

    // Chat Popup Variables
    private lateinit var windowManager: WindowManager
    private var chatPopupView: View? = null
    private var chatListener: ChildEventListener? = null
    private lateinit var chatRef: DatabaseReference
    private var serviceStartTime: Long = 0

    private var installedAppsList: List<ApplicationInfo>? = null
    private val TAG = "MyDeviceService"

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "BannikidServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceUtil.getDeviceId(this)
        serviceStartTime = System.currentTimeMillis()
        Log.d(TAG, "✅ Service Created. Device ID: $deviceId")

        // Initialize Managers
        callLogManager = CallLogManager(this)
        contactManager = ContactManager(this)
        smsManager = SmsManager(this)
        appUsageManager = AppUsageManager(this)
        locationManager = LocationManager(this)
        installedAppsManager = InstalledAppsManager(this)

        // FIX: DevicePolicyManager का सही उपयोग किया गया है
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        ttsManager = TextToSpeechManager(this)
        commandManager = CommandManager(this)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scheduleDailyCleanup()
        loadAllInstalledApps()
    }

    private fun loadAllInstalledApps() {
        try {
            // FIX: pm.getInstalledApplications को ठीक से कॉल किया गया है
            installedAppsList = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            Log.d(TAG, "Loaded ${installedAppsList?.size ?: 0} installed apps.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load app list", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service is starting...")

        // FIX: Start foreground service with proper timing to avoid Android's time limit restrictions
        startForegroundServiceSafely()

        // Start other components after foreground service is established
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                Log.d(TAG, "Initializing service components...")
                startDataUpdates()
                listenForChatMessages()
                Log.d(TAG, "🎧 Starting command listener for device: $deviceId")
                commandManager.listenForCommands(deviceId, database)
                Log.d(TAG, "✅ Command listener started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error initializing service components: ${e.message}", e)
            }
        }, 1000) // Delay to ensure foreground service is properly established

        return START_STICKY
    }

    // FIX: Safe foreground service start with proper error handling
    private fun startForegroundServiceSafely() {
        try {
            val notification = createNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // FIX: Use the correct foreground service type for data sync
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                Log.d(TAG, "✅ Foreground service started with DATA_SYNC type")
            } else {
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "✅ Foreground service started (legacy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start foreground service: ${e.message}", e)

            // FIX: Fallback - try to start without foreground service type
            try {
                val notification = createNotification()
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "✅ Foreground service started with fallback method")
            } catch (fallbackException: Exception) {
                Log.e(TAG, "❌ Foreground service fallback also failed: ${fallbackException.message}", fallbackException)

                // FIX: If all else fails, try to start service without foreground at all
                // This is not ideal but prevents the service from crashing
                Log.w(TAG, "⚠️ Starting service without foreground - this may cause issues")
                try {
                    val notification = createNotification()
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIFICATION_ID, notification)
                    Log.d(TAG, "✅ Service started with notification only")
                } catch (notificationException: Exception) {
                    Log.e(TAG, "❌ Even notification failed: ${notificationException.message}", notificationException)
                }
            }
        }
    }

    private fun startDataUpdates() {
        dataSyncTimer?.cancel()
        dataSyncTimer = Timer()
        dataSyncTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    if (AppState.isRealtimeSessionActive) {
                        Log.d(TAG, "Real-time session is active, skipping periodic data sync.")
                        val deviceNode = database.child("devices").child(deviceId)
                        startPeriodicUpdates(deviceNode)
                        return
                    }

                    Log.d(TAG, "Running periodic data sync...")
                    if (deviceId.isEmpty()) {
                        Log.w(TAG, "Device ID is empty, skipping sync.")
                        return
                    }
                    val deviceNode = database.child("devices").child(deviceId)
                    startPeriodicUpdates(deviceNode)
                    callLogManager.sync(deviceId, database)
                    contactManager.sync(deviceId, database)
                    smsManager.sync(deviceId, database)
                    appUsageManager.sync(deviceId, database)
                    locationManager.sync(deviceId, database)
                    installedAppsList?.let { appList ->
                        installedAppsManager.sync(deviceId, database, appList)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in data sync timer", e)
                }
            }
        }, 5000, 10 * 60 * 1000)
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Device Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Banniguard")
            .setContentText("Device is protected.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private fun scheduleDailyCleanup() {
        Log.d(TAG, "Scheduling daily cleanup worker.")
        val cleanupWorkRequest = PeriodicWorkRequestBuilder<CleanupRecordingsWorker>(1, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "CleanupRecordingsWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupWorkRequest
        )
    }

    private fun listenForChatMessages() {
        if (::deviceId.isInitialized) {
            chatRef = database.child("deviceChat").child(deviceId)
            if (chatListener != null) {
                chatRef.removeEventListener(chatListener!!)
            }
            Log.d(TAG, "Starting to listen for chat messages.")
            chatListener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    try {
                        val message = snapshot.getValue(DeviceChatMessage::class.java)
                        if (message != null && message.timestamp > serviceStartTime && message.sentBy == "PARENT") {
                            Log.d(TAG, "New parent message received, showing popup.")
                            showChatPopup(message.message ?: "...")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing chat message", e)
                    }
                }
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Chat listener cancelled.", error.toException())
                }
            }
            chatRef.addChildEventListener(chatListener!!)
        }
    }

    @SuppressLint("InflateParams")
    private fun showChatPopup(message: String) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show chat popup, overlay permission not granted.")
            return
        }

        if (chatPopupView != null) {
            removeChatPopup()
        }

        val contextThemeWrapper = ContextThemeWrapper(this, R.style.Theme_Banniguard)
        val inflater = LayoutInflater.from(contextThemeWrapper)
        chatPopupView = inflater.inflate(R.layout.popup_chat, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 100

        val tvParentMessage = chatPopupView!!.findViewById<TextView>(R.id.tvParentMessage)
        val etKidReply = chatPopupView!!.findViewById<EditText>(R.id.etKidReply)
        val btnSendReply = chatPopupView!!.findViewById<Button>(R.id.btnSendReply)
        val btnClosePopup = chatPopupView!!.findViewById<ImageButton>(R.id.btnClosePopup)

        tvParentMessage.text = message
        btnClosePopup.setOnClickListener { removeChatPopup() }
        btnSendReply.setOnClickListener {
            val replyText = etKidReply.text.toString().trim()
            if (replyText.isNotEmpty()) {
                val replyMessage = DeviceChatMessage(replyText, System.currentTimeMillis(), "KID")
                chatRef.push().setValue(replyMessage)
                removeChatPopup()
            }
        }

        try {
            windowManager.addView(chatPopupView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding chat popup view", e)
        }
    }

    private fun removeChatPopup() {
        if (chatPopupView != null && chatPopupView?.windowToken != null) {
            try {
                windowManager.removeView(chatPopupView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing chat popup view", e)
            }
        }
        chatPopupView = null
    }

    private fun startPeriodicUpdates(deviceNode: DatabaseReference) {
        val battery = getBatteryPercentage()
        val updates = mapOf(
            "status" to "online",
            "battery" to battery,
            "lastUpdated" to System.currentTimeMillis()
        )
        deviceNode.updateChildren(updates)
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, iFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level == -1 || scale == -1) -1 else (level / scale.toFloat() * 100).toInt()
        } catch (e: Exception) { -1 }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "☠️ Service is being destroyed.")

        try {
            // Stop foreground service properly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            Log.d(TAG, "✅ Foreground service stopped properly")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping foreground service: ${e.message}", e)
        }

        // Cancel timers
        dataSyncTimer?.cancel()
        dataSyncTimer = null

        // Remove listeners
        if (chatListener != null && ::chatRef.isInitialized) {
            chatRef.removeEventListener(chatListener!!)
        }

        // Clean up UI
        removeChatPopup()

        Log.d(TAG, "✅ Service cleanup completed")
    }
}
