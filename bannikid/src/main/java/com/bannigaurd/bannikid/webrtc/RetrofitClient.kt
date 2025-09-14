package com.bannigaurd.bannikid.webrtc

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

object RetrofitClient {
    private const val TWILIO_BASE_URL = "https://api.twilio.com/"
    private const val XIRSYS_BASE_URL = "https://global.xirsys.net/"

    // Twilio API client - using existing TwilioApiService interface
    val twilioInstance: TwilioApiService by lazy {
        Retrofit.Builder()
            .baseUrl(TWILIO_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TwilioApiService::class.java)
    }

    // Xirsys API client (fallback)
    val xirsysInstance: IceServerApi by lazy {
        Retrofit.Builder()
            .baseUrl(XIRSYS_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IceServerApi::class.java)
    }
}

interface IceServerApi {
    @GET("_turn/MyFirstApp")
    suspend fun getIceServers(@Header("Authorization") auth: String): retrofit2.Response<IceServerResponse>
}

data class IceServerResponse(
    val ice_servers: List<IceServerInfo>
)

data class IceServerInfo(
    val url: String,
    val urls: String? = null,
    val username: String? = null,
    val credential: String? = null
)

// Alternative response format for Xirsys API
data class XirsysResponse(
    val s: String, // status
    val v: XirsysData
)

data class XirsysData(
    val iceServers: List<XirsysIceServer>
)

data class XirsysIceServer(
    val url: String,
    val username: String? = null,
    val credential: String? = null
)
