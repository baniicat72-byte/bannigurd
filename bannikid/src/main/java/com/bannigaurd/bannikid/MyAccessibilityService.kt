// bannikid/MyAccessibilityService.kt

package com.bannigaurd.bannikid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.provider.Settings
import java.util.*
import android.app.admin.DevicePolicyManager // DevicePolicyManager के लिए नया इंपोर्ट
import android.content.ComponentName // ComponentName के लिए नया इंपोर्ट

class MyAccessibilityService : AccessibilityService() {

    private var swipePath: Path? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private val deviceAdminManager by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val adminComponent by lazy {
        ComponentName(this, MyDeviceAdminReceiver::class.java)
    }

    companion object {
        var instance: MyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        wm.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        Log.d("AccessibilityService", "Service connected. Screen size: $screenWidth x $screenHeight")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            if (packageName == applicationContext.packageName || isLauncher(packageName)) return
            val prefs = getSharedPreferences("BANNIKID_APP_CONTROLS", Context.MODE_PRIVATE)
            if (prefs.getBoolean(packageName, false)) {
                blockApp()
            }
        }
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        // यह चेक करने के लिए कि सर्विस चालू है या नहीं
        val serviceName = ComponentName(this, MyAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(serviceName) ?: false
    }

    fun performTouch(action: Int, x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return

        val scaledX = (x / viewWidth) * screenWidth
        val scaledY = (y / viewHeight) * screenHeight
        Log.d("AccessibilityService", "Touch action: $action at ($scaledX, $scaledY)")

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                swipePath = Path()
                swipePath?.moveTo(scaledX, scaledY)
            }
            MotionEvent.ACTION_MOVE -> {
                swipePath?.lineTo(scaledX, scaledY)
            }
            MotionEvent.ACTION_UP -> {
                swipePath?.lineTo(scaledX, scaledY)
                swipePath?.let {
                    val gestureBuilder = GestureDescription.Builder()
                    val duration = if (it.isEmpty) 50L else 200L
                    gestureBuilder.addStroke(GestureDescription.StrokeDescription(it, 0, duration))
                    dispatchGesture(gestureBuilder.build(), null, null)
                }
                swipePath = null
            }
        }
    }

    fun performKeyAction(action: String) {
        Log.d("AccessibilityService", "Performing key action: $action")
        val systemAction = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        performGlobalAction(systemAction)
    }

    // FIX: अब यह `lockDevice()` मेथड `DeviceAdminManager` को कॉल करेगा
    fun lockDevice() {
        if (deviceAdminManager.isAdminActive(adminComponent)) {
            deviceAdminManager.lockNow()
            Log.d("AccessibilityService", "Device locked remotely.")
        } else {
            Log.e("AccessibilityService", "Device admin not active. Cannot lock device.")
        }
    }

    private fun isLauncher(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, 0)
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    private fun blockApp() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(homeIntent)
    }

    override fun onInterrupt() {
        Log.w("AccessibilityService", "Service was interrupted.")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.d("AccessibilityService", "Service has been destroyed.")
    }
}