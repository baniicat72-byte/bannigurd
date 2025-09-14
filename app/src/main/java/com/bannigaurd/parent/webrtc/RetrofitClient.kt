package com.bannigaurd.parent.webrtc

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

// यह Retrofit का एक सिंगल इंस्टेंस बनाने के लिए है
object RetrofitClient {
    private const val TWILIO_BASE_URL = "https://api.twilio.com/"

    // FIX: Add OkHttpClient with timeout configuration
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Twilio API client - using existing TwilioApiService interface
    val twilioInstance: TwilioApiService by lazy {
        Retrofit.Builder()
            .baseUrl(TWILIO_BASE_URL)
            .client(okHttpClient) // FIX: Add OkHttpClient with timeouts
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TwilioApiService::class.java)
    }

    // Keep the old instance for backward compatibility
    val instance: TwilioApiService get() = twilioInstance
}
