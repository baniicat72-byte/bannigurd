package com.bannigaurd.bannikid

// Device Chat Message Class
data class DeviceChatMessage(
    val message: String? = null,
    val timestamp: Long = 0,
    val sentBy: String? = null // "PARENT" or "KID"
) {
    constructor() : this(null, 0, null)
}