package com.bannigaurd.bannikid.webrtc

import retrofit2.Response
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// यह इंटरफ़ेस Twilio API से बात करने का तरीका बताता है
interface TwilioApiService {
    // ✅ FIX: URL में {ACCOUNT_SID} को एक वेरिएबल की तरह बनाया गया
    @POST("2010-04-01/Accounts/{accountSid}/Tokens.json")
    suspend fun getIceServers(
        @Header("Authorization") auth: String,
        // ✅ FIX: @Path एनोटेशन से बताया गया कि accountSid की वैल्यू कहाँ डालनी है
        @Path("accountSid") accountSid: String
    ): Response<IceServerResponse>
}

// Note: IceServerResponse and IceServer classes are defined in RetrofitClient.kt
