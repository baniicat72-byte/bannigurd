package com.bannigaurd.bannikid

import android.app.Notification
import com.bannigaurd.bannikid.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bannigaurd.bannikid.workers.UploadRecordingWorker
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class CallRecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var phoneNumber: String? = null
    private var callStartTime: Long = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        phoneNumber = intent?.getStringExtra("PHONE_NUMBER") ?: "Unknown"
        startForegroundService()
        startRecording()
        return START_STICKY
    }

    private fun startRecording() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "rec_${timestamp}.amr"
        audioFile = File(externalCacheDir, fileName)

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(audioFile?.absolutePath)
            try {
                prepare()
                start()
                callStartTime = System.currentTimeMillis()
            } catch (e: IOException) {
                // Handle error
            }
        }
    }

    private fun stopRecording() {
        mediaRecorder?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                // Handle error
            }
        }
        mediaRecorder = null

        val callEndTime = System.currentTimeMillis()
        val duration = (callEndTime - callStartTime) / 1000

        if (audioFile != null && audioFile!!.exists()) {
            scheduleUpload(audioFile!!.absolutePath, phoneNumber!!, duration)
        }
    }

    private fun scheduleUpload(filePath: String, number: String, duration: Long) {
        val data = Data.Builder()
            .putString("FILE_PATH", filePath)
            .putString("PHONE_NUMBER", number)
            .putLong("TIMESTAMP", callStartTime)
            .putLong("DURATION", duration)
            .build()

        val uploadWorkRequest = OneTimeWorkRequestBuilder<UploadRecordingWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(this).enqueue(uploadWorkRequest)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    private fun startForegroundService() {
        val channelId = "CallRecordingServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Call Recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BanniGuard")
            .setContentText("Call recording in progress...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        startForeground(2, notification) // ID 1 से अलग होनी चाहिए
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
