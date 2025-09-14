package com.bannigaurd.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class RecordingsFragment : Fragment() {

    private lateinit var recordingsRecyclerView: RecyclerView
    private lateinit var recordingsAdapter: RecordingsAdapter
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var noRecordingsTextView: TextView
    private lateinit var databaseReference: DatabaseReference
    private var deviceId: String? = null
    private val recordingList = mutableListOf<Recording>()
    private var groupedList = mutableListOf<RecordingGroup>()

    companion object {
        private const val ARG_DEVICE_ID = "DEVICE_ID"
        fun newInstance(deviceId: String) =
            RecordingsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEVICE_ID, deviceId)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            deviceId = it.getString(ARG_DEVICE_ID)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_recordings, container, false)

        recordingsRecyclerView = view.findViewById(R.id.recordingsRecyclerView)
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar)
        noRecordingsTextView = view.findViewById(R.id.noRecordingsTextView)

        recordingsRecyclerView.layoutManager = LinearLayoutManager(context)
        recordingsAdapter = RecordingsAdapter(requireContext(), groupedList)
        recordingsRecyclerView.adapter = recordingsAdapter

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (deviceId != null) {
            databaseReference = FirebaseDatabase.getInstance().getReference("recordings").child(deviceId!!)
            fetchRecordings()
        } else {
            handleError("Device ID not found.")
        }
    }

    private fun fetchRecordings() {
        loadingProgressBar.visibility = View.VISIBLE
        noRecordingsTextView.visibility = View.GONE
        recordingsRecyclerView.visibility = View.GONE

        databaseReference.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                recordingList.clear()
                if (snapshot.exists()) {
                    for (recordingSnapshot in snapshot.children) {
                        recordingSnapshot.getValue(Recording::class.java)?.let {
                            recordingList.add(it)
                        }
                    }
                    groupAndDisplayRecordings()
                } else {
                    handleError("No recordings found.")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if(isAdded) handleError("Failed to load recordings: ${error.message}")
            }
        })
    }

    private fun groupAndDisplayRecordings() {
        if (!isAdded || recordingList.isEmpty()) {
            handleError("No recordings found.")
            return
        }

        noRecordingsTextView.visibility = View.GONE
        recordingsRecyclerView.visibility = View.VISIBLE
        loadingProgressBar.visibility = View.GONE

        val groupedByNumber = recordingList.groupBy { it.phoneNumber ?: "Unknown" }
        groupedList.clear()

        for ((number, recordings) in groupedByNumber) {
            val mostRecent = recordings.maxByOrNull { it.timestamp }
            val displayName = mostRecent?.phoneNumber ?: "Unknown"

            groupedList.add(
                RecordingGroup(
                    identifier = number,
                    displayName = displayName,
                    number = number,
                    recordings = recordings.sortedByDescending { it.timestamp }
                )
            )
        }

        groupedList.sortByDescending { it.recordings.firstOrNull()?.timestamp ?: 0 }
        recordingsAdapter.notifyDataSetChanged()
    }

    private fun handleError(message: String) {
        if(isAdded) {
            loadingProgressBar.visibility = View.GONE
            recordingsRecyclerView.visibility = View.GONE
            noRecordingsTextView.text = message
            noRecordingsTextView.visibility = View.VISIBLE
        }
    }

    override fun onStop() {
        super.onStop()
        recordingsAdapter.stopPlayback()
    }
}
