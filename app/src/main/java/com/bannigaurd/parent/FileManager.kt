package com.bannigaurd.parent

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object FileManager {

    private const val APP_FOLDER = "BanniGuard"

    // ✅ FIX: इसे private से public कर दिया गया है
    fun getAppDirectory(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            APP_FOLDER
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun downloadFile(context: Context, url: String, originalFileName: String, source: String) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(url)
            val fileName = "${source}_${System.currentTimeMillis()}_${originalFileName}"

            val request = DownloadManager.Request(uri)
                .setTitle(originalFileName)
                .setDescription("Downloading from BanniGuard...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$APP_FOLDER/$fileName")

            downloadManager.enqueue(request)
            Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to start download.", Toast.LENGTH_SHORT).show()
        }
    }

    fun getSavedFiles(): List<SavedFile> {
        val directory = getAppDirectory()
        val files = directory.listFiles() ?: return emptyList()

        return files.mapNotNull { file ->
            val parts = file.name.split("_", limit = 3)
            if (parts.size < 3) return@mapNotNull null

            val source = parts[0]
            val originalName = parts[2]
            val type = getFileType(file)

            SavedFile(
                path = file.absolutePath,
                name = originalName,
                size = file.length(),
                dateModified = file.lastModified(),
                type = type,
                source = source
            )
        }.sortedByDescending { it.dateModified }
    }

    fun openFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this file.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open file.", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteFile(filePath: String): Boolean {
        val file = File(filePath)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    private fun getMimeType(file: File): String? {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun getFileType(file: File): FileType {
        val mimeType = getMimeType(file) ?: return FileType.FILE
        return when {
            mimeType.startsWith("image/") -> FileType.IMAGE
            mimeType.startsWith("audio/") -> FileType.AUDIO
            mimeType.startsWith("video/") -> FileType.VIDEO
            else -> FileType.FILE
        }
    }
}