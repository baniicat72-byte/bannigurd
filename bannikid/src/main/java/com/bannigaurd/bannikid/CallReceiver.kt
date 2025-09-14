    package com.bannigaurd.bannikid

    import android.content.BroadcastReceiver
    import android.content.Context
    import android.content.Intent
    import android.os.Build
    import android.telephony.TelephonyManager
    import androidx.core.content.ContextCompat

    class CallReceiver : BroadcastReceiver() {
        companion object {
            private var isRecording = false
        }

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                when (state) {
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        if (!isRecording) {
                            isRecording = true
                            startRecordingService(context, phoneNumber)
                        }
                    }
                    TelephonyManager.EXTRA_STATE_IDLE, TelephonyManager.EXTRA_STATE_RINGING -> {
                        if (isRecording) {
                            isRecording = false
                            context.stopService(Intent(context, CallRecordingService::class.java))
                        }
                    }
                }
            }
        }

        private fun startRecordingService(context: Context, phoneNumber: String?) {
            // ✅ FIX: Using ContextCompat to handle service start for all Android versions safely.
            // This avoids the ForegroundServiceStartNotAllowedException on newer Android.
            val serviceIntent = Intent(context, CallRecordingService::class.java).apply {
                putExtra("PHONE_NUMBER", phoneNumber)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }