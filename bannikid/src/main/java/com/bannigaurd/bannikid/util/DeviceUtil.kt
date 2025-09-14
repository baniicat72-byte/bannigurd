package com.bannigaurd.bannikid.util

import android.content.Context
import android.provider.Settings

object DeviceUtil {
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    }
}
