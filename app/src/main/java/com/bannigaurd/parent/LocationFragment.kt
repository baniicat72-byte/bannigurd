    package com.bannigaurd.parent

    import android.annotation.SuppressLint
    import android.os.Bundle
    import android.view.LayoutInflater
    import android.view.View
    import android.view.ViewGroup
    import android.webkit.JavascriptInterface
    import android.webkit.WebView
    import android.webkit.WebViewClient
    import android.widget.ProgressBar
    import android.widget.Toast
    import androidx.fragment.app.Fragment
    import com.google.firebase.auth.ktx.auth
    import com.google.firebase.database.DataSnapshot
    import com.google.firebase.database.DatabaseError
    import com.google.firebase.database.ValueEventListener
    import com.google.firebase.database.ktx.database
    import com.google.firebase.firestore.ktx.firestore
    import com.google.firebase.ktx.Firebase
    import java.util.Date // <-- IMPORT ADDED HERE

    class LocationFragment : Fragment() {

        private lateinit var webView: WebView
        private lateinit var progressBar: ProgressBar
        private var childDeviceId: String? = null

        private val auth = Firebase.auth
        private val firestore = Firebase.firestore
        private val rtdb = Firebase.database.reference
        private var locationListener: ValueEventListener? = null

        // --- 👇 PASTE YOUR API KEY HERE 👇 ---
        private val GOOGLE_MAPS_API_KEY = "AIzaSyAnuTPyKBaRz-DH-GZEA_oLyw8XaImba48"
        // --- 👆 PASTE YOUR API KEY HERE 👆 ---

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            val view = inflater.inflate(R.layout.fragment_location, container, false)

            webView = view.findViewById(R.id.webView)
            progressBar = view.findViewById(R.id.progressBar)

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true

            webView.addJavascriptInterface(WebAppInterface(), "Android")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    webView.evaluateJavascript("javascript:setApiKey('${GOOGLE_MAPS_API_KEY}')", null)
                }
            }

            webView.loadUrl("file:///android_asset/LocationMap.html")

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
                if (!isAdded) return@addOnSuccessListener
                val devices = doc.get("linkedDevices") as? List<String>
                childDeviceId = (activity?.application as? MyApp)?.childDeviceId ?: devices?.firstOrNull()

                if (childDeviceId != null) {
                    attachLocationListener()
                } else {
                    handleError("No active device found.")
                }
            }
        }

        private fun attachLocationListener() {
            val locationRef = rtdb.child("locations").child(childDeviceId!!).child("liveLocation")

            locationListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded || !snapshot.exists()) return

                    val location = snapshot.getValue(LocationData::class.java)
                    if (location != null) {
                        val jsCommand = "javascript:updateLiveLocation(${location.latitude}, ${location.longitude}, '${Date(location.timestamp).toLocaleString()}')"
                        webView.evaluateJavascript(jsCommand, null)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    handleError("Failed to load location.")
                }
            }
            locationRef.addValueEventListener(locationListener!!)
        }

        private fun handleError(message: String) {
            if(isAdded) {
                progressBar.visibility = View.GONE
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

        inner class WebAppInterface {
            @JavascriptInterface
            fun requestLocationRefresh() {
                childDeviceId?.let {
                    rtdb.child("commands").child(it).child("requestLocation").setValue(true)
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Asking kid's device to turn on location...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            if (locationListener != null && childDeviceId != null) {
                rtdb.child("locations").child(childDeviceId!!).child("liveLocation").removeEventListener(locationListener!!)
            }
        }
    }