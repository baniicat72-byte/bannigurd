package com.bannigaurd.bannikid

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * WorkManager Worker for handling permission grants as ultimate fallback
 * This ensures permission processing even if other channels fail
 */
class PermissionWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    private val TAG = "PermissionWorker"

    override fun doWork(): Result {
        val permissionType = inputData.getString("PERMISSION_TYPE") ?: return Result.failure()
        val deviceId = inputData.getString("DEVICE_ID")
        val ablyApiKey = inputData.getString("ABLY_API_KEY")
        val requestedAction = inputData.getString("REQUESTED_ACTION")
        val timestamp = inputData.getLong("TIMESTAMP", 0)

        Log.d(TAG, "🔄 Processing $permissionType permission via WorkManager")
        Log.d(TAG, "📱 Device: $deviceId, Action: $requestedAction")

        try {
            // Start RealtimeService with permission data
            val serviceIntent = Intent(context, RealtimeService::class.java).apply {
                action = when (permissionType) {
                    "CAMERA" -> AppConstants.START_CAMERA_ACTION
                    "MIC" -> AppConstants.START_AUDIO_ACTION
                    else -> return Result.failure()
                }
                putExtra("DEVICE_ID", deviceId)
                putExtra("ABLY_API_KEY", ablyApiKey)
                putExtra("PERMISSION_TYPE", permissionType)
                putExtra("WORKER_TIMESTAMP", timestamp)
            }

            ContextCompat.startForegroundService(context, serviceIntent)
            Log.d(TAG, "✅ WorkManager: RealtimeService started for $permissionType")

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ WorkManager failed to start service: ${e.message}", e)
            return Result.failure()
        }
    }
}
