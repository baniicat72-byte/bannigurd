    package com.bannigaurd.bannikid.managers

    import android.util.Log
    import com.bannigaurd.bannikid.FileItem
    import java.io.File

    class FileManager {

        fun listFilesForPath(path: String): List<FileItem> {
            val fileList = mutableListOf<FileItem>()
            try {
                val directory = File(path)
                if (directory.exists() && directory.isDirectory) {
                    val files = directory.listFiles()
                    if (files != null) {
                        for (file in files) {
                            // Chhupi hui files ko ignore karein
                            if (file.name.startsWith(".")) {
                                continue
                            }
                            fileList.add(
                                FileItem(
                                    name = file.name,
                                    path = file.absolutePath,
                                    isDirectory = file.isDirectory,
                                    size = if (file.isDirectory) 0L else file.length(),
                                    lastModified = file.lastModified()
                                )
                            )
                        }
                    }
                }
                // Files aur folders ko alag-alag sort karein
                fileList.sortWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() }))
            } catch (e: Exception) {
                Log.e("FileManager", "Error listing files for path: $path", e)
            }
            return fileList
        }
    }