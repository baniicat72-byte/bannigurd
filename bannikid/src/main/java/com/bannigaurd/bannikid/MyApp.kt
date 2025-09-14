package com.bannigaurd.bannikid // <-- पैकेज का नाम ध्यान से देखें

import android.app.Application
import com.cloudinary.android.MediaManager
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // यह लाइन सुनिश्चित करती है कि डेटा ऑफलाइन भी उपलब्ध रहे
        try {
            Firebase.database.setPersistenceEnabled(true)
        } catch (e: Exception) {
            // यह एरर तब आता है जब ऐप पहले से चल रही हो, इसे अनदेखा किया जा सकता है
        }

        val config = mapOf(
            "cloud_name" to "daykj03oc",
            "api_key" to "258863751592291",
            "api_secret" to "Bva4o1x02SeWMj7HN8DWtufGlnc"
        )

        try {
            MediaManager.init(this, config)
        } catch (e: IllegalStateException) {
            // Already initialized
        }
    }
}