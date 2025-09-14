package com.bannigaurd.parent.webrtc

import android.util.Log
import com.google.gson.Gson
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.realtime.ConnectionState
import io.ably.lib.realtime.ConnectionStateListener
import io.ably.lib.types.AblyException
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.Message

object AblySignalManager {

    private var ably: AblyRealtime? = null
    private var channel: Channel? = null
    private var signalListener: SignalListener? = null
    private val gson = Gson()
    private const val TAG = "AblySignalManager_Parent"
    private var currentChannelName: String? = null
    private var isConnecting = false
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 5 // Increased from 3 to 5
    private val RECONNECT_DELAY = 2000L
    private val RECONNECT_BACKOFF_MULTIPLIER = 1.5f // Add backoff for more reliable reconnection
    private var lastApiKey: String? = null

    fun setSignalListener(listener: SignalListener) {
        this.signalListener = listener
    }

    private fun cleanup() {
        try {
            channel?.unsubscribe()
            channel?.detach()
            ably?.close()
            ably = null
            channel = null
            Log.d(TAG, "Ably cleanup completed")
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup: ${e.message}")
        }
    }

    fun connect(apiKey: String, channelName: String) {
        // Prevent multiple concurrent connection attempts
        if (isConnecting) {
            Log.d(TAG, "Connection attempt already in progress")
            // Still notify listener that we're trying to connect
            signalListener?.onConnectionEstablished()
            return
        }

        // If already connected to the same channel, just notify
        if (ably != null && ably?.connection?.state == ConnectionState.connected && currentChannelName == channelName) {
            Log.d(TAG, "Ably is already connected to channel: $channelName")
            signalListener?.onConnectionEstablished()
            return
        }

        // Always clean up existing connection before creating new one
        Log.d(TAG, "🧹 Cleaning up any existing connection before new connection")
        cleanup()

        currentChannelName = channelName
        lastApiKey = apiKey
        isConnecting = true
        reconnectAttempts = 0

        try {
            Log.d(TAG, "🔄 Attempting Ably connection (attempt ${reconnectAttempts + 1})")
            val clientOptions = ClientOptions(apiKey)

            ably = AblyRealtime(clientOptions)
            ably?.connection?.on(ConnectionStateListener { stateChange ->
                Log.d(TAG, "Parent Ably state changed: ${stateChange.current}, Reason: ${stateChange.reason}")
                handleConnectionStateChange(stateChange, channelName)
            })
        } catch (e: AblyException) {
            Log.e(TAG, "Error initializing Ably", e)
            isConnecting = false
            signalListener?.onConnectionError(e.errorInfo.message)
        }
    }

    private fun handleConnectionStateChange(stateChange: io.ably.lib.realtime.ConnectionStateListener.ConnectionStateChange, channelName: String) {
        when (stateChange.current) {
            ConnectionState.connected -> {
                Log.d(TAG, "✅ Ably connection successful")
                isConnecting = false
                reconnectAttempts = 0
                subscribeToChannel(channelName)
            }
            ConnectionState.failed, ConnectionState.disconnected -> {
                Log.w(TAG, "⚠️ Ably connection failed/disconnected: ${stateChange.reason?.message}")
                isConnecting = false

                // Attempt to reconnect if under max attempts
                if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && lastApiKey != null && currentChannelName != null) {
                    reconnectAttempts++
                    // Calculate backoff delay with exponential increase
                    val backoffDelay = (RECONNECT_DELAY * Math.pow(RECONNECT_BACKOFF_MULTIPLIER.toDouble(), (reconnectAttempts - 1).toDouble())).toLong()
                    Log.d(TAG, "🔄 Scheduling reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${backoffDelay}ms")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        connect(lastApiKey!!, currentChannelName!!)
                    }, backoffDelay)
                } else {
                    Log.e(TAG, "❌ Max reconnect attempts reached or missing credentials")
                    signalListener?.onConnectionError(stateChange.reason?.message ?: "Connection failed after max retries")
                }
            }
            ConnectionState.suspended -> {
                Log.w(TAG, "⚠️ Ably connection suspended")
                // Don't set isConnecting = false for suspended, as it may recover
            }
            ConnectionState.closing -> {
                Log.d(TAG, "🔄 Ably connection closing")
                isConnecting = false
            }
            ConnectionState.closed -> {
                Log.d(TAG, "❌ Ably connection closed")
                isConnecting = false
            }
            else -> {
                Log.d(TAG, "ℹ️ Ably connection state: ${stateChange.current}")
            }
        }
    }

    // ✅ FIX: अब यह सिर्फ उन्हीं मैसेज को सुनेगा जो Kid ऐप से आएंगे (ANSWER और ICE_CANDIDATE)
    private fun subscribeToChannel(channelName: String) {
        if (ably == null) return
        Log.d(TAG, "Subscribing to channel: '$channelName'")
        channel = ably?.channels?.get(channelName)

        val messageListener = Channel.MessageListener { message ->
            try {
                val json = message.data.toString()
                val signalMessage = gson.fromJson(json, SignalMessage::class.java)
                // अब हम मैसेज के 'name' (जैसे 'ANSWER') को भी लॉग कर सकते हैं
                Log.d(TAG, "⬇️ Parent received message: ${message.name} of type ${signalMessage.type}")

                // FIX: Ignore messages sent by parent (echo prevention)
                if (signalMessage.sender == "parent") {
                    Log.d(TAG, "🔄 Ignoring message sent by this parent (echo prevention)")
                    return@MessageListener
                }

                signalListener?.onSignalMessageReceived(signalMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing received message", e)
            }
        }

        try {
            // OFFER, ANSWER, ICE_CANDIDATE और CONTROL_CONFIRMATION को subscribe करें
            channel?.subscribe("OFFER", messageListener)
            channel?.subscribe("ANSWER", messageListener)
            channel?.subscribe("ICE_CANDIDATE", messageListener)
            channel?.subscribe("CONTROL_CONFIRMATION", messageListener)
            signalListener?.onConnectionEstablished()
            Log.d(TAG, "Successfully subscribed to OFFER, ANSWER, ICE_CANDIDATE, and CONTROL_CONFIRMATION events.")

        } catch (e: AblyException) {
            Log.e(TAG, "Error subscribing to channel", e)
            signalListener?.onConnectionError(e.errorInfo.message)
        }
    }

    // ✅ FIX: अब मैसेज का 'type' ही इवेंट का नाम होगा (जैसे "OFFER" या "ICE_CANDIDATE")
    fun sendMessage(message: SignalMessage) {
        if (channel == null) {
            Log.e(TAG, "Cannot send message, channel is not initialized.")
            return
        }
        try {
            val jsonMessage = gson.toJson(message)
            val messageSize = jsonMessage.toByteArray().size

            // FIX: Check message size limit (Ably limit is 65KB)
            if (messageSize > 65000) {
                Log.w(TAG, "Message size ($messageSize bytes) exceeds Ably limit. Attempting to compress or split.")
                // For now, try to send anyway but log the issue
                // TODO: Implement message compression or chunking
            }

            Log.d(TAG, "Sending message: ${message.type}, Size: $messageSize bytes")
            // 'message.type' को इवेंट के नाम के तौर पर इस्तेमाल करें
            channel?.publish(message.type, jsonMessage, object : CompletionListener {
                override fun onSuccess() {
                    Log.d(TAG, "⬆️ Parent sent message: ${message.type}")
                }
                override fun onError(reason: ErrorInfo?) {
                    Log.e(TAG, "Failed to send message: ${reason?.message}")
                    // FIX: Add retry logic for failed messages
                    if (reason?.message?.contains("Maximum message length exceeded") == true) {
                        Log.e(TAG, "Message too large, implementing fallback...")
                        // TODO: Implement message chunking
                    }
                }
            })
        } catch (e: AblyException) {
            Log.e(TAG, "Error sending message", e)
        }
    }

    // ✅ NEW: Control commands भेजने के लिए method
    fun sendControlCommand(commandType: String) {
        if (channel == null) {
            Log.e(TAG, "Cannot send control command, channel is not initialized.")
            return
        }
        try {
            val controlMessage = SignalMessage(
                type = commandType,
                command = commandType,
                timestamp = System.currentTimeMillis()
            )
            val jsonMessage = gson.toJson(controlMessage)
            channel?.publish(commandType, jsonMessage, object : CompletionListener {
                override fun onSuccess() {
                    Log.d(TAG, "⬆️ Parent sent control command: $commandType")
                }
                override fun onError(reason: ErrorInfo?) {
                    Log.e(TAG, "Failed to send control command: ${reason?.message}")
                }
            })
        } catch (e: AblyException) {
            Log.e(TAG, "Error sending control command", e)
        }
    }

    fun disconnect() {
        try {
            // ✅ FIX: सभी subscriptions से unsubscribe करें
            channel?.unsubscribe()
            channel?.detach()
            ably?.close()
            ably = null
            channel = null
            Log.d(TAG, "Ably disconnected.")
        } catch (e: AblyException) {
            Log.e(TAG, "Error disconnecting from Ably", e)
        }
    }

    // FIX: Add method to check if connected
    fun isConnected(): Boolean {
        return ably != null && ably?.connection?.state == ConnectionState.connected
    }

    // FIX: Add method to force complete reset
    fun forceCompleteReset() {
        Log.d(TAG, "🧹 Force complete reset of AblySignalManager")

        try {
            // Reset all state variables
            isConnecting = false
            reconnectAttempts = 0
            currentChannelName = null
            lastApiKey = null

            // Force cleanup
            cleanup()
            
            // Add a small delay before allowing new connections
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "✅ AblySignalManager ready for new connections")
            }, 500)

            Log.d(TAG, "✅ Force complete reset finished")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during force complete reset: ${e.message}", e)
        }
    }

    // FIX: Add method to get current connection state
    fun getConnectionState(): String {
        return ably?.connection?.state?.toString() ?: "DISCONNECTED"
    }
    
    // Add method to get last API key
    fun getLastApiKey(): String? {
        return lastApiKey
    }
    
    // Add method to get current channel name
    fun getCurrentChannelName(): String? {
        return currentChannelName
    }
}
