package com.bannigaurd.parent

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class FilesAdapter(
    private val context: Context,
    private var fileGroups: List<FileItem>,
    private val onFolderClick: (FileItem) -> Unit,
    private val onFileClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FilesAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.ivFileIcon)
        private val name: TextView = itemView.findViewById(R.id.tvFileName)
        private val details: TextView = itemView.findViewById(R.id.tvFileDetails)
        private val downloadButton: ImageButton = itemView.findViewById(R.id.btnDownloadFile)
        val detailsContainer: LinearLayout = itemView.findViewById(R.id.details_container)

        init {
            itemView.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    val item = fileGroups[bindingAdapterPosition]
                    if (item.isDirectory) {
                        item.isExpanded = !item.isExpanded
                        onFolderClick(item)
                        notifyItemChanged(bindingAdapterPosition)
                    }
                }
            }
        }

        fun bind(item: FileItem) {
            name.text = item.name
            icon.setImageResource(R.drawable.ic_files)
            icon.setColorFilter(ContextCompat.getColor(context, R.color.folder_yellow))
            downloadButton.visibility = View.GONE
            details.text = "Folder"

            detailsContainer.removeAllViews()
            if (item.isExpanded) {
                detailsContainer.visibility = View.VISIBLE
            } else {
                detailsContainer.visibility = View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = fileGroups[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = fileGroups.size

    fun addFilesToFolder(folderPath: String, files: List<FileItem>) {
        val folderPosition = fileGroups.indexOfFirst { it.path == folderPath }
        if (folderPosition != -1) {
            val viewHolder = (context as? androidx.appcompat.app.AppCompatActivity)?.findViewById<RecyclerView>(R.id.rvFiles)?.findViewHolderForAdapterPosition(folderPosition) as? ViewHolder
            viewHolder?.detailsContainer?.apply {
                removeAllViews()
                files.forEach { file ->
                    val fileView = LayoutInflater.from(context).inflate(R.layout.item_file_child, null) // नया लेआउट

                    val fileName = fileView.findViewById<TextView>(R.id.tvChildFileName)
                    val fileDetails = fileView.findViewById<TextView>(R.id.tvChildFileDetails)
                    val downloadBtn = fileView.findViewById<ImageButton>(R.id.btnChildDownload)

                    fileName.text = file.name
                    val sdf = SimpleDateFormat("dd MMM yy", Locale.getDefault())
                    fileDetails.text = "${sdf.format(Date(file.lastModified))} • ${Formatter.formatShortFileSize(context, file.size)}"

                    if (file.url != null) {
                        downloadBtn.setColorFilter(ContextCompat.getColor(context, R.color.status_green))
                    } else {
                        downloadBtn.setColorFilter(ContextCompat.getColor(context, R.color.light_gray))
                    }

                    downloadBtn.setOnClickListener { onFileClick(file) }
                    addView(fileView)
                }
            }
        }
    }
}