package com.bannigaurd.bannikid.managers

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import android.util.Log
import com.bannigaurd.bannikid.InstalledApp
import com.google.firebase.database.DatabaseReference
import java.io.ByteArrayOutputStream

class InstalledAppsManager(private val context: Context) {

    // ✅ sync function अब बाहर से ऐप्स की लिस्ट लेता है
    fun sync(deviceId: String, dbRef: DatabaseReference, installedApps: List<ApplicationInfo>) {
        if (!isUsageStatsAllowed()) {
            Log.w("InstalledAppsManager", "Usage Stats permission not granted.")
            return
        }

        val pm = context.packageManager
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val end = System.currentTimeMillis()
        val start = end - 1000 * 60 * 60 * 24 // Last 24 hours
        val usageStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        val foregroundApp = getForegroundApp(usageStatsManager)

        val appsList = mutableListOf<InstalledApp>()

        // ✅ बाहर से मिली 'installedApps' लिस्ट पर लूप चलाएं
        for (appInfo in installedApps) {

            // ✅ सिर्फ वही ऐप्स चुनें जो सिस्टम ऐप नहीं हैं
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                try {
                    val packageName = appInfo.packageName
                    val packageInfo = pm.getPackageInfo(packageName, 0)
                    val appUsageStat = usageStats.find { it.packageName == packageName }

                    appsList.add(
                        InstalledApp(
                            appName = appInfo.loadLabel(pm).toString(),
                            packageName = packageName,
                            installTime = packageInfo.firstInstallTime,
                            totalTimeInForeground = appUsageStat?.totalTimeInForeground ?: 0,
                            appIconBase64 = drawableToBase64(appInfo.loadIcon(pm)),
                            isForeground = (packageName == foregroundApp)
                        )
                    )
                } catch (e: Exception) {
                    Log.e("InstalledAppsManager", "Error processing app: ${appInfo.packageName}", e)
                }
            }
        }
        dbRef.child("apps").child(deviceId).setValue(appsList)
    }

    private fun getForegroundApp(usageStatsManager: UsageStatsManager): String? {
        val time = System.currentTimeMillis()
        val appUsage = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 30, time)
        return appUsage?.sortedByDescending { it.lastTimeUsed }?.firstOrNull()?.packageName
    }

    private fun isUsageStatsAllowed(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
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