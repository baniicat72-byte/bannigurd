package com.bannigaurd.bannikid.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class UploadRecordingWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString("FILE_PATH") ?: return Result.failure()
        val phoneNumber = inputData.getString("PHONE_NUMBER") ?: "Unknown"
        val timestamp = inputData.getLong("TIMESTAMP", 0)
        val duration = inputData.getLong("DURATION", 0)

        val deviceId = applicationContext.getSharedPreferences("BanniKidPrefs", Context.MODE_PRIVATE)
            .getString("DEVICE_ID", null) ?: return Result.failure()

        val file = File(filePath)
        if (!file.exists()) return Result.failure()

        return suspendCoroutine { continuation ->
            MediaManager.get().upload(filePath)
                .option("resource_type", "video")
                .callback(object : UploadCallback {
                    override fun onStart(requestId: String) {}
                    override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}

                    override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                        val downloadUrl = resultData["secure_url"] as? String
                        if (downloadUrl != null) {
                            val recordingData = mapOf(
                                "phoneNumber" to phoneNumber,
                                "timestamp" to timestamp,
                                "duration" to duration,
                                "url" to downloadUrl
                            )
                            val databaseRef = FirebaseDatabase.getInstance().getReference("recordings/$deviceId")
                            databaseRef.push().setValue(recordingData).addOnCompleteListener {
                                file.delete()
                                if (it.isSuccessful) {
                                    continuation.resume(Result.success())
                                } else {
                                    continuation.resume(Result.failure())
                                }
                            }
                        } else {
                            file.delete()
                            continuation.resume(Result.failure())
                        }
                    }

                    override fun onError(requestId: String, error: ErrorInfo) {
                        file.delete()
                        continuation.resume(Result.failure())
                    }

                    override fun onReschedule(requestId: String, error: ErrorInfo) {}
                }).dispatch()
        }
    }
}