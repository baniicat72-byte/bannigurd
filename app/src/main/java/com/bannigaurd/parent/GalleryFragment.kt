package com.bannigaurd.parent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class GalleryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var noPhotosTextView: TextView
    private var childDeviceId: String? = null
    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_gallery, container, false)
        recyclerView = view.findViewById(R.id.rvGallery)
        progressBar = view.findViewById(R.id.progressBarGallery)
        noPhotosTextView = view.findViewById(R.id.tvNoPhotos)
        recyclerView.layoutManager = GridLayoutManager(context, 3)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fetchLinkedDevice()
    }

    private fun fetchLinkedDevice() {
        progressBar.visibility = View.VISIBLE
        val parentUid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(parentUid).get().addOnSuccessListener { doc ->
            if(!isAdded) return@addOnSuccessListener
            val devices = doc.get("linkedDevices") as? List<String>
            if (!devices.isNullOrEmpty()) {
                childDeviceId = devices[0]
                fetchGalleryImages()
            } else {
                progressBar.visibility = View.GONE
                noPhotosTextView.visibility = View.VISIBLE
            }
        }
    }

    private fun fetchGalleryImages() {
        childDeviceId ?: return
        val galleryRef = rtdb.child("gallery").child(childDeviceId!!)
        galleryRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                progressBar.visibility = View.GONE
                val images = mutableListOf<GalleryImage>()
                snapshot.children.forEach {
                    it.getValue(GalleryImage::class.java)?.let { image -> images.add(image) }
                }

                if (images.isEmpty()) {
                    noPhotosTextView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    noPhotosTextView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    images.sortByDescending { it.dateAdded ?: 0 }
                    val galleryAdapter = GalleryAdapter(requireContext(), images, childDeviceId!!)
                    recyclerView.adapter = galleryAdapter
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if(isAdded) {
                    progressBar.visibility = View.GONE
                    noPhotosTextView.text = "Failed to load images."
                    noPhotosTextView.visibility = View.VISIBLE
                }
            }
        })
    }
}