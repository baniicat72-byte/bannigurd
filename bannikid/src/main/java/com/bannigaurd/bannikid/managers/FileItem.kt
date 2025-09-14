package com.bannigaurd.bannikid

import com.google.firebase.database.PropertyName

data class FileItem(
    val name: String? = null,
    val path: String? = null,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val url: String? = null,

    // --- FIX: @PropertyName का उपयोग करें ---
    @get:PropertyName("isDirectory")
    @set:PropertyName("isDirectory")
    var isDirectory: Boolean = false
) {
    // Firebase के लिए खाली constructor
    constructor() : this(null, null, 0L, 0L, null, false)
}