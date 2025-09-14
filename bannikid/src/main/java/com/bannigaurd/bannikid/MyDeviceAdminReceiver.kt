package com.bannigaurd.bannikid

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d("DeviceAdmin", "Device Admin enabled.")
        Toast.makeText(context, "Bannikid device admin enabled.", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d("DeviceAdmin", "Device Admin disabled.")
        Toast.makeText(context, "Warning: Bannikid device admin has been disabled.", Toast.LENGTH_LONG).show()
    }
}