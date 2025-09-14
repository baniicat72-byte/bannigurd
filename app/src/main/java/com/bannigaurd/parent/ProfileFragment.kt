package com.bannigaurd.parent

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth

class ProfileFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_profile, container, false)

        auth = FirebaseAuth.getInstance()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

        val currentUser = auth.currentUser
        val ivProfilePhoto = view.findViewById<ImageView>(R.id.ivProfilePhoto)
        val tvUserName = view.findViewById<TextView>(R.id.tvUserName)
        val tvUserEmail = view.findViewById<TextView>(R.id.tvUserEmail)
        val btnLogout = view.findViewById<TextView>(R.id.btnLogout)

        // --- 👇 YEH BUTTON ADD KIYA GAYA HAI 👇 ---
        val btnPairedDevices = view.findViewById<TextView>(R.id.btnPairedDevices)
        btnPairedDevices.setOnClickListener {
            // यह नई Activity खोलेगा जिसे हम अगले स्टेप में बनाएंगे
            startActivity(Intent(activity, DeleteDeviceActivity::class.java))
        }

        if (currentUser != null) {
            tvUserName.text = currentUser.displayName ?: "Banniguard User"
            tvUserEmail.text = currentUser.email

            Glide.with(this)
                .load(currentUser.photoUrl)
                .placeholder(R.drawable.ic_contacts)
                .circleCrop()
                .into(ivProfilePhoto)
        }

        btnLogout.setOnClickListener {
            auth.signOut()
            googleSignInClient.signOut().addOnCompleteListener {
                val intent = Intent(activity, AuthRouterActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                activity?.finish()
            }
        }

        return view
    }
}