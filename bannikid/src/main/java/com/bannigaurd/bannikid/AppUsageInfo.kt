package com.bannigaurd.bannikid

import com.google.firebase.database.PropertyName

data class AppUsageInfo(
    val appName: String?,
    val packageName: String?,
    val installTime: Long,
    val usageTimeToday: Long,
    val dataUsageToday: Long,
    val lastTimeUsed: Long,
    val appIconBase64: String?,
    @get:PropertyName("isForeground") @set:PropertyName("isForeground")
    var isForeground: Boolean,
    @get:PropertyName("isBlocked") @set:PropertyName("isBlocked")
    var isBlocked: Boolean, // <-- इसे val से var कर दिया गया है
    val timeLimit: Long
) {
    constructor() : this(null, null, 0, 0, 0, 0, null, false, false, 0L)
}