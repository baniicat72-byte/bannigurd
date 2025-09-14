    package com.bannigaurd.bannikid

    data class NotificationItem(
        val appName: String? = null,
        val packageName: String? = null,
        val title: String? = null,
        val text: String? = null,
        val timestamp: Long = 0,
        val appIconBase64: String? = null // <-- आइकन के लिए नई फील्ड
    ) {
        // Firebase के लिए खाली कंस्ट्रक्टर
        constructor() : this(null, null, null, null, 0, null)
    }