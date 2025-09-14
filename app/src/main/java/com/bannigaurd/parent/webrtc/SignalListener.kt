package com.bannigaurd.parent.webrtc

// यह इंटरफ़ेस WebRTCManager और AblySignalManager को जोड़ता है
interface SignalListener {
    fun onSignalMessageReceived(message: SignalMessage)
    fun onConnectionEstablished()
    fun onConnectionError(error: String)
}