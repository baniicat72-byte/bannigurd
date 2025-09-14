package com.bannigaurd.parent

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.gson.Gson

class GalleryAdapter(
    private val context: Context,
    private val images: List<GalleryImage>,
    private val childDeviceId: String
) : RecyclerView.Adapter<GalleryAdapter.ImageViewHolder>() {

    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.ivGalleryPhoto)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_gallery_photo, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val image = images[position]
        Glide.with(context)
            .load(image.url)
            .centerCrop()
            .placeholder(R.drawable.ic_gallery) // प्लेसहोल्डर इमेज
            .into(holder.imageView)

        holder.itemView.setOnClickListener {
            val intent = Intent(context, PhotoViewActivity::class.java).apply {
                putExtra("IMAGE_URL", image.url)
                putExtra("CHILD_DEVICE_ID", childDeviceId)
                // पूरी इमेज ऑब्जेक्ट को JSON स्ट्रिंग के रूप में भेजें
                putExtra("GALLERY_IMAGE_JSON", Gson().toJson(image))
            }
            context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int = images.size
}