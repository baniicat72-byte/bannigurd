    package com.bannigaurd.parent.webrtc

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

    // यह Twilio से मिलने वाले जवाब को संभालने के लिए डेटा क्लास है
    data class IceServerResponse(
        val ice_servers: List<IceServer>
    )

    data class IceServer(
        val url: String,
        val username: String?,
        val credential: String? // Twilio पासवर्ड को 'credential' कहता है
    )