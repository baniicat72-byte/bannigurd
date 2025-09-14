    package com.bannigaurd.parent

    import android.os.Bundle
    import android.util.Log
    import android.widget.Toast
    import androidx.appcompat.app.AppCompatActivity
    import androidx.fragment.app.Fragment
    import com.google.android.material.bottomnavigation.BottomNavigationView
    import com.google.android.material.navigation.NavigationBarView
    import com.google.firebase.auth.ktx.auth
    import com.google.firebase.firestore.ktx.firestore
    import com.google.firebase.ktx.Firebase

    class DashboardActivity : AppCompatActivity() {

        // बच्चे का active deviceId स्टोर करने के लिए
        private var activeChildDeviceId: String? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_dashboard)

            val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
            bottomNav.setOnItemSelectedListener(navListener)

            // सबसे पहले बच्चे का deviceId लोड करें
            fetchActiveDeviceId { deviceId ->
                if (deviceId != null) {
                    activeChildDeviceId = deviceId
                    // Application class में भी deviceId सेट कर दें
                    (application as? MyApp)?.childDeviceId = deviceId

                    // अगर savedInstanceState null है, तो DashboardFragment दिखाएँ
                    if (savedInstanceState == null) {
                        supportFragmentManager.beginTransaction().replace(
                            R.id.fragment_container,
                            DashboardFragment()
                        ).commitAllowingStateLoss()
                    }
                } else {
                    Toast.makeText(this, "No linked device found. Please link a device.", Toast.LENGTH_LONG).show()
                    // आप यहाँ LinkDeviceActivity पर भी भेज सकते हैं
                }
            }
        }

        private fun fetchActiveDeviceId(callback: (String?) -> Unit) {
            val parentUid = Firebase.auth.currentUser?.uid
            if (parentUid == null) {
                callback(null)
                return
            }
            Firebase.firestore.collection("users").document(parentUid).get()
                .addOnSuccessListener { document ->
                    val devices = document.get("linkedDevices") as? List<String>
                    // पहला डिवाइस ID लौटा दें
                    callback(devices?.firstOrNull())
                }
                .addOnFailureListener {
                    callback(null)
                }
        }

        private val navListener = NavigationBarView.OnItemSelectedListener { item ->
            var selectedFragment: Fragment? = null

            when (item.itemId) {
                R.id.nav_dashboard -> selectedFragment = DashboardFragment()
                R.id.nav_features -> {
                    // FeaturesFragment बनाने का सही तरीका, deviceId के साथ
                    if (activeChildDeviceId != null) {
                        selectedFragment = FeaturesFragment().apply {
                            arguments = Bundle().apply {
                                putString("DEVICE_ID", activeChildDeviceId)
                            }
                        }
                    } else {
                        Toast.makeText(this, "No device selected.", Toast.LENGTH_SHORT).show()
                    }
                }
                R.id.nav_profile -> selectedFragment = ProfileFragment()
            }

            if (selectedFragment != null) {
                supportFragmentManager.beginTransaction().replace(
                    R.id.fragment_container,
                    selectedFragment
                ).commitAllowingStateLoss()
            }
            true
        }

        // ✅ FIX: Handle back button press for instant disconnect
        override fun onBackPressed() {
            Log.d("DashboardActivity", "🚨 Back button pressed - checking for active streams")

            // Check if there's an active camera stream fragment
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (currentFragment is CameraStreamFragment) {
                Log.d("DashboardActivity", "📷 Camera stream fragment found - forcing instant disconnect")
                // Force immediate disconnect before closing
                currentFragment.disconnectAndClose()
                return // Don't call super.onBackPressed() yet
            }

            // Check if there's an active audio stream fragment
            if (currentFragment is LiveAudioFragment) {
                Log.d("DashboardActivity", "🎤 Audio stream fragment found - forcing instant disconnect")
                // Force immediate disconnect before closing
                currentFragment.disconnectAndClose()
                return // Don't call super.onBackPressed() yet
            }

            // No active streams, proceed normally
            super.onBackPressed()
        }

        // ✅ FIX: Handle app close/destroy for instant cleanup
        override fun onDestroy() {
            super.onDestroy()
            Log.d("DashboardActivity", "🚨 DashboardActivity onDestroy - forcing instant cleanup")

            try {
                // Force cleanup of any remaining WebRTC connections
                val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (currentFragment is CameraStreamFragment) {
                    Log.d("DashboardActivity", "📷 Force cleaning up camera stream in onDestroy")
                    currentFragment.performImmediateUICleanup()
                }

                if (currentFragment is LiveAudioFragment) {
                    Log.d("DashboardActivity", "🎤 Force cleaning up audio stream in onDestroy")
                    currentFragment.performImmediateCleanup()
                }

                // Force garbage collection
                System.gc()
                Log.d("DashboardActivity", "✅ DashboardActivity cleanup completed instantly")

            } catch (e: Exception) {
                Log.w("DashboardActivity", "⚠️ Error during DashboardActivity cleanup: ${e.message}")
            }
        }

        // ✅ FIX: Handle app going to background
        override fun onPause() {
            super.onPause()
            Log.d("DashboardActivity", "⏸️ DashboardActivity paused")

            // Optional: Could add logic here to pause streams when app goes to background
            // But for now, let streams continue as they might be intentional
        }

        // ✅ FIX: Handle app coming back to foreground
        override fun onResume() {
            super.onResume()
            Log.d("DashboardActivity", "▶️ DashboardActivity resumed")

            // Optional: Could add logic here to resume streams when app comes back
        }
    }
