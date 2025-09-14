package com.bannigaurd.bannikid.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class CleanupRecordingsWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val deviceId = applicationContext.getSharedPreferences("BanniKidPrefs", Context.MODE_PRIVATE)
            .getString("DEVICE_ID", null) ?: return Result.failure()

        val threeDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)
        val databaseRef = FirebaseDatabase.getInstance().getReference("recordings/$deviceId")

        return try {
            val snapshot = databaseRef.orderByChild("timestamp").endAt(threeDaysAgo.toDouble()).get().await()

            if (snapshot.exists()) {
                for (childSnapshot in snapshot.children) {
                    childSnapshot.ref.removeValue().await()
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}