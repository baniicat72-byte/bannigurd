package com.bannigaurd.parent

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedFileAdapter(
    private val context: Context,
    private val savedFiles: MutableList<SavedFile>
) : RecyclerView.Adapter<SavedFileAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.ivFileIcon)
        val name: TextView = itemView.findViewById(R.id.tvFileName)
        val details: TextView = itemView.findViewById(R.id.tvFileDetails)
        val deleteButton: ImageButton = itemView.findViewById(R.id.btnDeleteFile)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_saved_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = savedFiles[position]

        holder.name.text = file.name
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val dateString = sdf.format(Date(file.dateModified))
        holder.details.text = "From ${file.source} • $dateString"

        val iconRes = when(file.type) {
            FileType.IMAGE -> R.drawable.ic_gallery
            FileType.AUDIO -> R.drawable.ic_recordings
            FileType.VIDEO -> R.drawable.ic_live_camera
            FileType.FILE -> R.drawable.ic_files
        }
        holder.icon.setImageResource(iconRes)

        holder.itemView.setOnClickListener {
            FileManager.openFile(context, File(file.path))
        }

        holder.deleteButton.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Delete File")
                .setMessage("Are you sure you want to delete '${file.name}' from your device?")
                .setPositiveButton("Delete") { _, _ ->
                    if (FileManager.deleteFile(file.path)) {
                        savedFiles.removeAt(position)
                        notifyItemRemoved(position)
                        notifyItemRangeChanged(position, savedFiles.size)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun getItemCount(): Int = savedFiles.size
}