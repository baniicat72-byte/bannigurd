package com.bannigaurd.parent

import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class FilesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var noFilesTextView: TextView
    private lateinit var pathTextView: TextView
    private lateinit var filesAdapter: FilesAdapter

    private var childDeviceId: String? = null
    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference
    private lateinit var commandRef: DatabaseReference

    // Path को मैनेज करने के लिए
    private val pathStack = ArrayDeque<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_files, container, false)
        recyclerView = view.findViewById(R.id.rvFiles)
        progressBar = view.findViewById(R.id.progressBarFiles)
        noFilesTextView = view.findViewById(R.id.tvNoFiles)
        pathTextView = view.findViewById(R.id.tvCurrentPath)
        recyclerView.layoutManager = LinearLayoutManager(context)

        // बैक बटन को हैंडल करने के लिए
        pathTextView.setOnClickListener {
            // --- FIX: pop() को removeLast() से बदला गया ---
            if (pathStack.isNotEmpty()) {
                loadPath(pathStack.removeLast())
            }
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fetchLinkedDeviceAndLoadRoot()
    }

    private fun fetchLinkedDeviceAndLoadRoot() {
        progressBar.visibility = View.VISIBLE
        val parentUid = auth.currentUser?.uid ?: return

        firestore.collection("users").document(parentUid).get().addOnSuccessListener { document ->
            if (!isAdded) return@addOnSuccessListener
            childDeviceId = (document.get("linkedDevices") as? List<String>)?.firstOrNull()
            if (childDeviceId != null) {
                commandRef = rtdb.child("commands").child(childDeviceId!!)
                loadPath(Environment.getExternalStorageDirectory().absolutePath)
            } else {
                handleError("No device found.")
            }
        }
    }

    private fun loadPath(path: String) {
        val displayPath = path.removePrefix(Environment.getExternalStorageDirectory().absolutePath)
        pathTextView.text = if (displayPath.isEmpty()) "📂 /Internal Storage" else "📂 ...${displayPath.takeLast(25)}"

        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        noFilesTextView.visibility = View.VISIBLE
        noFilesTextView.text = "Loading files..."

        requestFilesList(path)

        val pathKey = Base64.encodeToString(path.toByteArray(), Base64.NO_WRAP)
        val filesRef = rtdb.child("files").child(childDeviceId!!).child(pathKey)

        filesRef.addListenerForSingleValueEvent(object: ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                progressBar.visibility = View.GONE
                val allItems = mutableListOf<FileItem>()
                snapshot.children.forEach {
                    it.getValue(FileItem::class.java)?.let { item -> allItems.add(item) }
                }

                if(allItems.isEmpty()) {
                    handleError("This folder is empty.")
                    return
                }

                noFilesTextView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE

                filesAdapter = FilesAdapter(requireContext(), allItems,
                    onFolderClick = { folder ->
                        // --- FIX: push(path) को addLast(path) से बदला गया ---
                        pathStack.addLast(path)
                        loadPath(folder.path!!)
                    },
                    onFileClick = { file ->
                        handleFileClick(file)
                    }
                )
                recyclerView.adapter = filesAdapter
            }

            override fun onCancelled(error: DatabaseError) {
                handleError("Failed to load files.")
            }
        })
    }

    private fun handleFileClick(fileItem: FileItem) {
        if (fileItem.url != null) {
            FileManager.downloadFile(requireContext(), fileItem.url, fileItem.name ?: "file", "Files")
        } else {
            Toast.makeText(context, "Requesting file from kid's device...", Toast.LENGTH_SHORT).show()
            val command = mapOf("value" to fileItem.path, "timestamp" to ServerValue.TIMESTAMP)
            commandRef.child("uploadFile").setValue(command)
        }
    }

    private fun requestFilesList(path: String) {
        val command = mapOf("value" to path, "timestamp" to ServerValue.TIMESTAMP)
        commandRef.child("listFiles").setValue(command)
    }

    private fun handleError(message: String) {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.GONE
        noFilesTextView.text = message
        noFilesTextView.visibility = View.VISIBLE
    }
}