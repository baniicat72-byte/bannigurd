package com.bannigaurd.parent

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bannigaurd.parent.FileManager
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RecordingsAdapter(
    private val context: Context,
    private var recordingGroups: List<RecordingGroup>
) : RecyclerView.Adapter<RecordingsAdapter.GroupViewHolder>() {

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingHolder: DetailViewHolder? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_recording_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(recordingGroups[position])
    }

    override fun getItemCount(): Int = recordingGroups.size

    fun stopPlayback() {
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        currentlyPlayingHolder?.resetUI()
        currentlyPlayingHolder = null
    }

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.tvCallerName)
        private val numberTextView: TextView = itemView.findViewById(R.id.tvPhoneNumber)
        private val countTextView: TextView = itemView.findViewById(R.id.tvRecordingCount)
        private val detailsContainer: LinearLayout = itemView.findViewById(R.id.details_container)

        init {
            itemView.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    val group = recordingGroups[bindingAdapterPosition]
                    group.isExpanded = !group.isExpanded
                    notifyItemChanged(bindingAdapterPosition)
                }
            }
        }

        fun bind(group: RecordingGroup) {
            nameTextView.text = group.displayName
            numberTextView.text = group.number
            countTextView.text = group.recordings.size.toString()
            detailsContainer.removeAllViews()

            if (group.isExpanded) {
                detailsContainer.visibility = View.VISIBLE
                group.recordings.forEach { recording ->
                    val detailView = LayoutInflater.from(context).inflate(R.layout.item_recording_detail, detailsContainer, false)
                    val detailViewHolder = DetailViewHolder(detailView, recording)
                    detailViewHolder.bind()
                    detailsContainer.addView(detailView)
                }
            } else {
                detailsContainer.visibility = View.GONE
            }
        }
    }

    inner class DetailViewHolder(private val view: View, private val recording: Recording) {
        private val timeText: TextView = view.findViewById(R.id.tvRecordingTime)
        private val durationText: TextView = view.findViewById(R.id.tvRecordingDuration)
        private val playButton: ImageButton = view.findViewById(R.id.playButton)
        private val downloadButton: ImageButton = view.findViewById(R.id.downloadButton)
        private val progressBar: ProgressBar = view.findViewById(R.id.playbackProgressBar)

        fun bind() {
            timeText.text = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()).format(Date(recording.timestamp))
            val minutes = TimeUnit.SECONDS.toMinutes(recording.duration)
            val seconds = recording.duration % 60
            durationText.text = String.format("Duration: %dm %ds", minutes, seconds)

            playButton.setOnClickListener {
                if (currentlyPlayingHolder == this) {
                    stopPlayback()
                } else {
                    stopPlayback()
                    currentlyPlayingHolder = this
                    playAudio(recording.url)
                }
            }
            downloadButton.setOnClickListener { downloadRecording(recording) }
        }

        fun resetUI() {
            progressBar.visibility = View.GONE
            playButton.setImageResource(R.drawable.ic_play)
        }

        private fun playAudio(url: String?) {
            if (url.isNullOrEmpty()) {
                Toast.makeText(context, "Recording URL not found.", Toast.LENGTH_SHORT).show()
                return
            }

            progressBar.visibility = View.VISIBLE
            playButton.setImageResource(R.drawable.ic_pause)

            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(url)
                    prepareAsync()
                } catch (e: IOException) {
                    stopPlayback()
                }
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ ->
                    stopPlayback()
                    true
                }
            }
        }
    }

    // --- 👇 सिर्फ यह फंक्शन बदला गया है 👇 ---
    private fun downloadRecording(recording: Recording) {
        if (recording.url.isNullOrEmpty()) {
            Toast.makeText(context, "Download URL not available.", Toast.LENGTH_SHORT).show()
            return
        }
        // फाइल का नाम तैयार करें
        val fileName = "rec_${recording.phoneNumber}_${recording.timestamp}.amr"

        // FileManager का इस्तेमाल करके डाउनलोड करें
        FileManager.downloadFile(context, recording.url, fileName, "Recordings")
    }
}
