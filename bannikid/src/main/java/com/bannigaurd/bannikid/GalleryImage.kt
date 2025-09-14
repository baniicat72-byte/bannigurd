package com.bannigaurd.bannikid

data class GalleryImage(
    val name: String = "",
    val url: String = "",
    val publicId: String = "",
    val dateAdded: Long = 0L
) {
    constructor() : this("", "", "", 0L)
}