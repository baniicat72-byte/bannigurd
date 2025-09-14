package com.bannigaurd.bannikid

import android.app.Notification
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.io.ByteArrayOutputStream
import java.util.Calendar

class MyNotificationListener : NotificationListenerService() {

    private val db = Firebase.database.reference
    private var deviceId: String? = null

    // Sirf in apps ki notification bhejein
    private val allowedApps = listOf(
        "com.whatsapp",
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.orca", // Messenger
        "org.telegram.messenger",
        "com.snapchat.android",
        "com.twitter.android"
    )

    override fun onCreate() {
        super.onCreate()
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Service connect hote hi purani notifications delete kar dein
        deviceId?.let {
            db.child("notifications").child(it).removeValue()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (deviceId == null || sbn == null) return

        val packageName = sbn.packageName

        // Check karein ki app allowed list me hai ya nahi
        if (packageName !in allowedApps) {
            return
        }

        // Check karein ki notification aaj ki hai ya nahi
        if (!isToday(sbn.postTime)) {
            return
        }

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        if (title.isNullOrBlank() || text.isNullOrBlank()) return

        var appName = packageName
        var iconBase64: String? = null

        try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            appName = pm.getApplicationLabel(appInfo).toString()
            val iconDrawable = pm.getApplicationIcon(appInfo)
            iconBase64 = drawableToBase64(iconDrawable)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e("MyNotificationListener", "App not found for package: $packageName")
        }

        val notificationItem = NotificationItem(
            appName = appName,
            packageName = packageName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            appIconBase64 = iconBase64
        )

        val notificationId = db.child("notifications").child(deviceId!!).push().key
        if (notificationId != null) {
            db.child("notifications").child(deviceId!!).child(notificationId).setValue(notificationItem)
        }
    }

    private fun isToday(timestamp: Long): Boolean {
        val notificationDate = Calendar.getInstance()
        notificationDate.timeInMillis = timestamp

        val today = Calendar.getInstance()

        return notificationDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                notificationDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    }

    private fun drawableToBase64(drawable: Drawable?): String? {
        if (drawable == null) return null
        return try {
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }
}