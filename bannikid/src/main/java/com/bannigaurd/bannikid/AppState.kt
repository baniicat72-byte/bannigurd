    package com.bannigaurd.bannikid

    // यह ऑब्जेक्ट ऐप की करेंट स्टेट को स्टोर करेगा
    object AppState {
        @Volatile
        var isRealtimeSessionActive = false
    }