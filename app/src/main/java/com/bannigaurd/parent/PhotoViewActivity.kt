package com.bannigaurd.parent

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bannigaurd.parent.FileManager
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson

class PhotoViewActivity : AppCompatActivity() {

    private val rtdb = Firebase.database.reference
    private var childDeviceId: String? = null
    private var galleryImage: GalleryImage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_view)

        val photoView: PhotoView = findViewById(R.id.photo_view)
        val downloadButton: Button = findViewById(R.id.btnDownload)
        val deleteButton: Button = findViewById(R.id.btnDelete)

        val imageUrl = intent.getStringExtra("IMAGE_URL")
        childDeviceId = intent.getStringExtra("CHILD_DEVICE_ID")
        val galleryImageJson = intent.getStringExtra("GALLERY_IMAGE_JSON")

        if (galleryImageJson != null) {
            galleryImage = Gson().fromJson(galleryImageJson, GalleryImage::class.java)
        }

        if (imageUrl != null) {
            Glide.with(this)
                .load(imageUrl)
                .into(photoView)
        } else {
            Toast.makeText(this, "Could not load image.", Toast.LENGTH_SHORT).show()
            finish()
        }

        downloadButton.setOnClickListener {
            imageUrl?.let { url -> downloadImage(url) }
        }

        deleteButton.setOnClickListener {
            if (childDeviceId != null && galleryImage != null) {
                sendDeleteCommand()
            } else {
                Toast.makeText(this, "Could not delete photo. Info missing.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- 👇 सिर्फ यह फंक्शन बदला गया है 👇 ---
    private fun downloadImage(url: String) {
        // फाइल का नाम तैयार करें
        val fileName = galleryImage?.displayName ?: "${System.currentTimeMillis()}.jpg"

        // FileManager का इस्तेमाल करके डाउनलोड करें
        FileManager.downloadFile(this, url, fileName, "Gallery")
    }

    private fun sendDeleteCommand() {
        val commandRef = rtdb.child("commands").child(childDeviceId!!).child("deletePhoto")
        commandRef.setValue(galleryImage)
            .addOnSuccessListener {
                Toast.makeText(this, "Delete command sent. Photo will be deleted shortly.", Toast.LENGTH_LONG).show()
                finish() // एक्टिविटी बंद कर दें
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to send delete command.", Toast.LENGTH_SHORT).show()
            }
    }
}
