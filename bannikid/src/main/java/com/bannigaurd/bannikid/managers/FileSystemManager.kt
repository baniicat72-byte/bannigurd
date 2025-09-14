package com.bannigaurd.bannikid.managers

import android.util.Base64
import android.util.Log
import com.bannigaurd.bannikid.FileItem
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class FileSystemManager(private val dbRef: DatabaseReference) {

    private val fileManager = FileManager()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private companion object {
        const val MAX_STORAGE_BYTES = 30 * 1024 * 1024 // 30 MB
    }

    // सिर्फ फाइल लिस्ट भेजता है
    fun handleFileListRequest(path: String, deviceId: String) {
        coroutineScope.launch {
            val localFiles = fileManager.listFilesForPath(path)
            val pathKey = Base64.encodeToString(path.toByteArray(), Base64.NO_WRAP)
            dbRef.child("files").child(deviceId).child(pathKey).setValue(localFiles)
                .addOnSuccessListener {
                    Log.d("FileSystemManager", "File list for path $path sent.")
                }
        }
    }

    // पेरेंट से कमांड आने पर फाइल अपलोड करता है और फिर डिलीट कर देता है
    fun handleFileUploadRequest(filePath: String, deviceId: String) {
        coroutineScope.launch {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e("FileSystemManager", "File not found for upload: $filePath")
                return@launch
            }

            val storageUsageRef = dbRef.child("devices").child(deviceId).child("storageUsage")
            val currentUsage = getCurrentStorageUsage(storageUsageRef)

            if (currentUsage + file.length() <= MAX_STORAGE_BYTES) {
                Log.d("FileSystemManager", "Quota available. Uploading ${file.name}...")
                val uploadResult = uploadFileToCloudinary(filePath)
                if (uploadResult != null) {
                    val (url, publicId) = uploadResult
                    // Firebase में पेरेंट को डाउनलोड URL भेजें
                    updateFileUrlInFirebase(filePath, url, deviceId)
                    // स्टोरेज यूसेज अपडेट करें
                    storageUsageRef.setValue(currentUsage + file.length())

                    // --- NEW: Cloudinary से तुरंत डिलीट करने के लिए कमांड भेजें ---
                    deleteFromCloudinary(publicId)
                }
            } else {
                Log.w("FileSystemManager", "Quota exceeded for user. Cannot upload ${file.name}")
            }
        }
    }

    // --- NEW: Cloudinary से फाइल डिलीट करने का फंक्शन ---
    private fun deleteFromCloudinary(publicId: String) {
        coroutineScope.launch {
            try {
                // MediaManager.get().destroy() is for signed requests. Use upload API for unsigned deletion.
                // This is a workaround as unsigned destroy is not directly in the SDK.
                // It requires setting up an upload preset in Cloudinary for deletion.
                // For simplicity, we will log this action. Proper implementation needs server-side logic or a signed request.
                Log.i("FileSystemManager", "File $publicId was uploaded and is now intended for deletion from Cloudinary to save space.")
                // In a real production app, you'd make a signed API call here to destroy the publicId.
                // MediaManager.get().destroy(publicId, callback) // This would require authentication
            } catch (e: Exception) {
                Log.e("FileSystemManager", "Error during Cloudinary delete request", e)
            }
        }
    }

    private fun updateFileUrlInFirebase(localPath: String, url: String, deviceId: String) {
        val parentPath = File(localPath).parent ?: return
        val pathKey = Base64.encodeToString(parentPath.toByteArray(), Base64.NO_WRAP)
        val fileNodeRef = dbRef.child("files").child(deviceId).child(pathKey)

        fileNodeRef.orderByChild("path").equalTo(localPath).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val fileKey = snapshot.children.first().key
                    if (fileKey != null) {
                        fileNodeRef.child(fileKey).child("url").setValue(url)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private suspend fun getCurrentStorageUsage(ref: DatabaseReference): Long {
        return suspendCancellableCoroutine { continuation ->
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (continuation.isActive) {
                        continuation.resume(snapshot.getValue(Long::class.java) ?: 0L)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    if (continuation.isActive) {
                        continuation.resume(0L)
                    }
                }
            })
        }
    }

    // यह अब URL के साथ public_id भी लौटाएगा
    private suspend fun uploadFileToCloudinary(filePath: String): Pair<String, String>? {
        return suspendCancellableCoroutine { continuation ->
            MediaManager.get().upload(filePath)
                .unsigned("banniguard")
                .option("resource_type", "auto")
                .callback(object : UploadCallback {
                    override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                        val url = resultData["secure_url"] as? String
                        val publicId = resultData["public_id"] as? String
                        if (url != null && publicId != null && continuation.isActive) {
                            continuation.resume(Pair(url, publicId))
                        } else if(continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                    override fun onError(requestId: String, error: ErrorInfo) {
                        Log.e("FileSystemManager", "Upload failed: ${error.description}")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                    override fun onStart(requestId: String) {}
                    override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}
                    override fun onReschedule(requestId: String, error: ErrorInfo) {}
                }).dispatch()
        }
    }
}