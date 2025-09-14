package com.bannigaurd.bannikid

import android.graphics.drawable.Drawable

enum class PermissionType {
    NORMAL,
    USAGE_STATS,
    ACCESSIBILITY,
    OVERLAY,
    APP_SETTINGS,
    ALL_FILES_ACCESS,
    NOTIFICATION_LISTENER,
    DEVICE_ADMIN,
    // ✅ FIX: निर्माता-विशिष्ट सेटिंग्स के लिए नया टाइप जोड़ा गया
    MANUFACTURER_SPECIFIC
}

data class PermissionItem(
    val id: String,
    val name: String,
    val description: String,
    val icon: Drawable,
    val permissionType: PermissionType,
    val permissionString: String? = null,
    var isGranted: Boolean = false
)