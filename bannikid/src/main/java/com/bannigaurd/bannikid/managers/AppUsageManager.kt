package com.bannigaurd.bannikid.managers

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Build
import android.util.Base64
import android.util.Log
import com.bannigaurd.bannikid.AppUsageInfo
import com.google.firebase.database.DatabaseReference
import java.io.ByteArrayOutputStream
import java.util.*

class AppUsageManager(private val context: Context) {

    fun sync(deviceId: String, dbRef: DatabaseReference) {
        if (!isUsageStatsAllowed()) {
            Log.w("AppUsageManager", "Usage Stats permission not granted.")
            return
        }

        val controlPrefs = context.getSharedPreferences("BANNIKID_APP_CONTROLS", Context.MODE_PRIVATE)
        val pm = context.packageManager
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        val foregroundApp = getForegroundApp(usageStatsManager)
        val usageList = mutableListOf<AppUsageInfo>()

        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val startOfToday = calendar.timeInMillis
        val end = System.currentTimeMillis()

        val usageStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday, end)
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (appInfo in apps) {
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                try {
                    val packageName = appInfo.packageName
                    val packageInfo = pm.getPackageInfo(packageName, 0)
                    val appUsageStat = usageStats.find { it.packageName == packageName }

                    usageList.add(
                        AppUsageInfo(
                            appName = appInfo.loadLabel(pm).toString(),
                            packageName = packageName,
                            installTime = packageInfo.firstInstallTime,
                            usageTimeToday = appUsageStat?.totalTimeInForeground ?: 0,
                            dataUsageToday = getAppNetworkUsage(networkStatsManager, appInfo.uid, startOfToday, end),
                            lastTimeUsed = appUsageStat?.lastTimeUsed ?: packageInfo.firstInstallTime,
                            appIconBase64 = drawableToBase64(appInfo.loadIcon(pm)),
                            isForeground = (packageName == foregroundApp),
                            isBlocked = controlPrefs.getBoolean(packageName, false),
                            timeLimit = controlPrefs.getLong("${packageName}_limit", 0L)
                        )
                    )
                } catch (e: Exception) { /* Ignore */ }
            }
        }
        dbRef.child("appUsage").child(deviceId).setValue(usageList)
    }

    @SuppressLint("MissingPermission")
    private fun getAppNetworkUsage(networkStatsManager: NetworkStatsManager, uid: Int, startTime: Long, endTime: Long): Long {
        var totalBytes = 0L
        try {
            // Mobile Data
            val mobileStats = networkStatsManager.querySummary(ConnectivityManager.TYPE_MOBILE, null, startTime, endTime)
            val bucket = NetworkStats.Bucket()
            while (mobileStats.hasNextBucket()) {
                mobileStats.getNextBucket(bucket)
                if (bucket.uid == uid) {
                    totalBytes += bucket.rxBytes + bucket.txBytes
                }
            }
            mobileStats.close()

            // WiFi Data
            val wifiStats = networkStatsManager.querySummary(ConnectivityManager.TYPE_WIFI, "", startTime, endTime)
            while (wifiStats.hasNextBucket()) {
                wifiStats.getNextBucket(bucket)
                if (bucket.uid == uid) {
                    totalBytes += bucket.rxBytes + bucket.txBytes
                }
            }
            wifiStats.close()
        } catch (e: Exception) {
            Log.e("AppUsageManager", "Could not get network stats for UID $uid", e)
        }
        return totalBytes
    }

    private fun getForegroundApp(usageStatsManager: UsageStatsManager): String? {
        val time = System.currentTimeMillis()
        val appUsage = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
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