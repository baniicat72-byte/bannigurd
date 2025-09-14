package com.bannigaurd.bannikid.managers

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import com.bannigaurd.bannikid.GalleryImage
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.firebase.database.DatabaseReference
import java.io.File

class GalleryManager(private val context: Context) {

    private val uploadedFilesPrefs = context.getSharedPreferences("UploadedFilesPrefs", Context.MODE_PRIVATE)

    fun sync(deviceId: String, dbRef: DatabaseReference) {
        val projection = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 30"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val filePathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val filePath = cursor.getString(filePathColumn)
                    if (filePath != null && !wasFileUploaded(filePath)) {
                        val file = File(filePath)
                        if (file.exists()) {
                            val displayName = cursor.getString(displayNameColumn)
                            val dateAdded = cursor.getLong(dateAddedColumn)
                            uploadFileToCloudinary(filePath, displayName, dateAdded, deviceId, dbRef)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GalleryManager", "Error querying gallery images", e)
        }
    }

    private fun wasFileUploaded(filePath: String): Boolean {
        return uploadedFilesPrefs.getBoolean(filePath, false)
    }

    private fun uploadFileToCloudinary(filePath: String, fileName: String, dateAdded: Long, deviceId: String, dbRef: DatabaseReference) {
        MediaManager.get().upload(filePath)
            .callback(object : UploadCallback {
                override fun onStart(requestId: String) {}
                override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}

                override fun onSuccess(requestId: String, resultData: MutableMap<Any?, Any?>) {
                    val publicId = resultData["public_id"] as? String ?: ""
                    val url = resultData["secure_url"] as? String ?: ""
                    Log.i("GalleryManager", "Image uploaded: $url")

                    val galleryData = GalleryImage(
                        name = fileName,
                        url = url,
                        publicId = publicId,
                        dateAdded = dateAdded
                    )

                    dbRef.child("gallery").child(deviceId).push().setValue(galleryData)
                        .addOnSuccessListener {
                            uploadedFilesPrefs.edit { putBoolean(filePath, true) }
                            Log.d("GalleryManager", "Marked file as uploaded: $filePath")
                        }
                }

                override fun onError(requestId: String, error: ErrorInfo) {
                    Log.e("GalleryManager", "Upload error: ${error.description}")
                }
                override fun onReschedule(requestId: String, error: ErrorInfo) {}
            }).dispatch()
    }

    fun deletePhotoFromStorage(deviceId: String, dbRef: DatabaseReference, image: GalleryImage) {
        Log.d("GalleryManager", "Request to delete photo: ${image.name}")
    }
}