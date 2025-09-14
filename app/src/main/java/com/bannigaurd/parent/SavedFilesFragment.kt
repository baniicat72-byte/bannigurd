package com.bannigaurd.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SavedFilesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var noFilesTextView: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_saved_files, container, false)
        recyclerView = view.findViewById(R.id.rvSavedFiles)
        noFilesTextView = view.findViewById(R.id.tvNoFiles)
        recyclerView.layoutManager = LinearLayoutManager(context)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSavedFiles()
    }

    private fun loadSavedFiles() {
        val savedFiles = FileManager.getSavedFiles().toMutableList()

        if (savedFiles.isEmpty()) {
            recyclerView.visibility = View.GONE
            noFilesTextView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            noFilesTextView.visibility = View.GONE
            recyclerView.adapter = SavedFileAdapter(requireContext(), savedFiles)
        }
    }
}