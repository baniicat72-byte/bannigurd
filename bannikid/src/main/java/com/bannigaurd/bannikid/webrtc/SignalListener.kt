package com.bannigaurd.bannikid.webrtc

interface SignalListener {
    fun onSignalMessageReceived(message: SignalMessage)
    fun onConnectionEstablished()
    fun onConnectionError(error: String)
}
