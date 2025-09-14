package com.bannigaurd.bannikid

data class InstalledApp(
    val appName: String? = null,
    val packageName: String? = null,
    val installTime: Long = 0,
    val totalTimeInForeground: Long = 0, // Milliseconds
    val appIconBase64: String? = null,
    var isForeground: Boolean = false
) {
    // Firebase के लिए खाली कंस्ट्रक्टर
    constructor() : this(null, null, 0, 0, null, false)
}