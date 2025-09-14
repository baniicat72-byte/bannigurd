package com.bannigaurd.parent

import com.google.firebase.database.Exclude
import com.google.firebase.database.PropertyName

// Call Log Classes
data class CallLog(
    val number: String? = null,
    val type: String? = null,
    val date: Long? = null,
    val duration: Long? = null,
    val name: String? = null
) {
    constructor() : this(null, null, null, null, null)
}

data class CallGroup(
    val identifier: String,
    val displayName: String,
    val number: String,
    val calls: List<CallLog>,
    var isExpanded: Boolean = false
)

// Device Info Class
data class DeviceInfo(
    val id: String,
    val name: String,
    val isOnline: Boolean
)

// Contact Class
data class Contact(
    val name: String? = null,
    val number: String? = null
) {
    constructor() : this(null, null)
}

// SMS Classes
data class SmsMessage(
    val address: String? = null,
    val body: String? = null,
    val date: Long? = null,
    val type: String? = null
) {
    constructor() : this(null, null, null, null)
}

data class SmsConversation(
    val address: String,
    var contactName: String,
    var lastMessage: String?,
    var timestamp: Long?
)

// File System Class
// --- THIS CLASS IS UPDATED ---
data class FileItem(
    val name: String? = null,
    val path: String? = null,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val url: String? = null,

    @get:PropertyName("isDirectory")
    @set:PropertyName("isDirectory")
    var isDirectory: Boolean = false,

    // --- NEW: Tree View (एक्सपेंडेबल लिस्ट) के लिए नई प्रॉपर्टीज़ ---
    @get:Exclude var depth: Int = 0,
    @get:Exclude var isExpanded: Boolean = false
) {
    constructor() : this(null, null, 0L, 0L, null, false, 0, false)
}

// Gallery Class
data class GalleryImage(
    val id: Long? = null,
    val url: String? = null,
    val dateAdded: Long? = null,
    val displayName: String? = null,
    val contentUri: String? = null
) {
    constructor() : this(null, null, null, null, null)
}

// Notification Classes
data class NotificationItem(
    val appName: String? = null,
    val packageName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val timestamp: Long = 0,
    val appIconBase64: String? = null
) {
    constructor() : this(null, null, null, null, 0, null)
}

data class NotificationConversation(
    val conversationId: String,
    val appName: String,
    val packageName: String,
    val senderName: String,
    var lastMessage: String,
    var timestamp: Long,
    var messageCount: Int = 0,
    var appIconBase64: String? = null
)

// Installed App Class
data class InstalledApp(
    val appName: String? = null,
    val packageName: String? = null,
    val installTime: Long = 0,
    val totalTimeInForeground: Long = 0,
    val appIconBase64: String? = null,
    @get:PropertyName("isForeground") @set:PropertyName("isForeground")
    var isForeground: Boolean = false
) {
    constructor() : this(null, null, 0, 0, null, false)
}

// Location Class
data class LocationData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = 0
) {
    constructor() : this(0.0, 0.0, 0)
}

// App Usage Info Class
data class AppUsageInfo(
    val appName: String? = null,
    val packageName: String? = null,
    val installTime: Long = 0,
    val usageTimeToday: Long = 0, // Milliseconds
    val dataUsageToday: Long = 0, // Bytes
    val lastTimeUsed: Long = 0,
    val appIconBase64: String? = null,
    @get:PropertyName("isForeground") @set:PropertyName("isForeground")
    var isForeground: Boolean = false,
    @get:PropertyName("isBlocked") @set:PropertyName("isBlocked")
    var isBlocked: Boolean = false,
    val timeLimit: Long = 0 // Minutes, 0 for no limit
) {
    constructor() : this(null, null, 0, 0, 0, 0, null, false, false, 0)
}

// Device Chat Message Class
data class DeviceChatMessage(
    val message: String? = null,
    val timestamp: Long = 0,
    val sentBy: String? = null // "PARENT" or "KID"
) {
    constructor() : this(null, 0, null)
}

// Call Recording Classes
data class Recording(
    val phoneNumber: String? = null,
    val timestamp: Long = 0,
    val duration: Long = 0, // in seconds
    val url: String? = null,
    val type: String? = "Incoming" // "Incoming" or "Outgoing"
) {
    constructor() : this(null, 0, 0, null, "Incoming")
}

data class RecordingGroup(
    val identifier: String,
    val displayName: String,
    val number: String,
    val recordings: List<Recording>,
    var isExpanded: Boolean = false
)

// Saved Files Classes
enum class FileType {
    IMAGE, AUDIO, VIDEO, FILE
}

data class SavedFile(
    val path: String,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val type: FileType,
    val source: String // Jaise "Gallery", "Recordings", "Files"
)