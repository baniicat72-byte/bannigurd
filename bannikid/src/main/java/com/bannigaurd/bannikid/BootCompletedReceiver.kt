package com.bannigaurd.bannikid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // यह सुनिश्चित करें कि यह 'BOOT_COMPLETED' एक्शन पर ही चले
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val serviceIntent = Intent(context, MyDeviceService::class.java)

            // Android 8 और नए वर्जन के लिए startForegroundService का इस्तेमाल करें
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}