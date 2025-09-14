// bannikid/webrtc/AblySignalManager.kt

package com.bannigaurd.bannikid.webrtc

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
    private val gson = Gson()
    private var listener: SignalListener? = null
    @Volatile var isConnected = false
    private const val TAG = "AblySignalManager_Kid"

    // ✅ FIX: Enhanced connection management with proper synchronization
    private var lastApiKey: String? = null
    private var currentChannelName: String? = null
    @Volatile private var isRetrying = false
    private var retryCount = 0
    private val maxRetries = 3
    private val baseRetryDelay = 1000L // 1 second
    private val messageQueue = mutableListOf<QueuedMessage>()
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lock = Object() // Synchronization lock

    // ✅ FIX: Better connection state management
    @Volatile private var isConnecting = false
    private var lastConnectionAttempt: Long = 0
    private val connectionCooldown = 2000L // 2 seconds between connection attempts
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 5

    private data class QueuedMessage(
        val message: SignalMessage,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun setSignalListener(signalListener: SignalListener) {
        listener = signalListener
    }

    // FIX: Add forceCompleteReset to reset state and cleanup
    fun forceCompleteReset() {
        Log.d(TAG, "🧹 Force complete reset of AblySignalManager")
        isConnecting = false
        retryCount = 0
        isRetrying = false
        currentChannelName = null
        lastApiKey = null
        disconnect()
        // Cancel any pending retries
        retryHandler.removeCallbacksAndMessages(null)
        messageQueue.clear()
    }

    fun getConnectionState(): String {
        return ably?.connection?.state?.name ?: "unknown"
    }

    // FIX: Add guarded reconnect method
    fun reconnect() {
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && lastApiKey != null && currentChannelName != null) {
            Log.d(TAG, "🔄 Attempting reconnect (${reconnectAttempts + 1}/${MAX_RECONNECT_ATTEMPTS})")
            connect(lastApiKey!!, currentChannelName!!)
        } else {
            Log.e(TAG, "❌ Reconnect failed: max attempts reached or missing credentials")
        }
    }

    fun connect(apiKey: String, channelName: String) {
        // FIX: Always cleanup before new connection
        Log.d(TAG, "🧹 Cleaning up any existing connection before new connection")
        disconnect()

        // FIX: Store credentials for retry
        this.lastApiKey = apiKey
        this.currentChannelName = channelName

        // FIX: Prevent multiple concurrent connection attempts
        val currentTime = System.currentTimeMillis()
        if (isConnecting && (currentTime - lastConnectionAttempt) < connectionCooldown) {
            Log.d(TAG, "⚠️ Connection attempt already in progress, ignoring")
            return
        }

        if (isConnected && ably != null) {
            Log.d(TAG, "✅ Kid is already connected.")
            subscribeToChannel(channelName)
            processQueuedMessages()
            return
        }

        // FIX: Reset retry state and set connection state
        retryCount = 0
        isRetrying = false
        isConnecting = true
        lastConnectionAttempt = currentTime

        attemptConnection(apiKey, channelName)
    }

    // FIX: Add retry mechanism with exponential backoff
    private fun attemptConnection(apiKey: String, channelName: String) {
        try {
            Log.d(TAG, "🔄 Attempting Ably connection (attempt ${retryCount + 1}/${maxRetries})")

            val clientOptions = ClientOptions(apiKey)
            ably = AblyRealtime(clientOptions)

            ably?.connection?.on(ConnectionStateListener { state ->
                Log.d(TAG, "Kid Ably state changed: ${state.current}, Reason: ${state.reason?.message}")
                when (state.current) {
                    ConnectionState.connected -> {
                        Log.d(TAG, "✅ Ably connection successful")
                        isConnected = true
                        isConnecting = false // FIX: Reset connecting state
                        retryCount = 0 // Reset retry count on success
                        subscribeToChannel(channelName)
                        processQueuedMessages()
                    }
                    ConnectionState.failed, ConnectionState.closed -> {
                        Log.w(TAG, "⚠️ Ably connection failed: ${state.current}, Reason: ${state.reason?.message}")
                        isConnected = false
                        isConnecting = false // FIX: Reset connecting state
                        listener?.onConnectionError(state.reason?.message ?: "Disconnected")

                        // FIX: Attempt retry if not already retrying and under max retries
                        if (!isRetrying && retryCount < maxRetries) {
                            scheduleRetry()
                        } else if (retryCount >= maxRetries) {
                            Log.e(TAG, "❌ Max retry attempts reached. Giving up.")
                            isRetrying = false
                        }
                    }
                    ConnectionState.connecting -> {
                        Log.d(TAG, "🔄 Ably connecting...")
                    }
                    ConnectionState.disconnected -> {
                        Log.w(TAG, "⚠️ Ably disconnected")
                        isConnected = false
                        isConnecting = false // FIX: Reset connecting state
                    }
                    else -> {
                        Log.d(TAG, "ℹ️ Ably state: ${state.current}")
                    }
                }
            })
        } catch (e: AblyException) {
            Log.e(TAG, "❌ Ably connection error: ${e.errorInfo.message}", e)
            listener?.onConnectionError(e.errorInfo.message)

            // FIX: Attempt retry on exception
            if (!isRetrying && retryCount < maxRetries) {
                scheduleRetry()
            }
        }
    }

    // FIX: Schedule connection retry with exponential backoff
    private fun scheduleRetry() {
        if (isRetrying || isConnecting) return

        isRetrying = true
        retryCount++

        val delay = baseRetryDelay * (1L shl (retryCount - 1)) // Exponential backoff
        Log.d(TAG, "⏰ Scheduling retry in ${delay}ms (attempt ${retryCount}/${maxRetries})")

        retryHandler.postDelayed({
            isRetrying = false
            if (lastApiKey != null && currentChannelName != null) {
                attemptConnection(lastApiKey!!, currentChannelName!!)
            }
        }, delay)
    }

    private fun subscribeToChannel(channelName: String) {
        if (ably == null) return
        channel = ably?.channels?.get(channelName)
        Log.d(TAG, "Subscribing to channel: '$channelName'")

        val messageListener = Channel.MessageListener { message ->
            try {
                val signalMessage = gson.fromJson(message.data.toString(), SignalMessage::class.java)
                Log.d(TAG, "⬇️ Kid received message: ${message.name} of type ${signalMessage.type}")
                Log.d(TAG, "📨 Message details - Name: ${message.name}, Type: ${signalMessage.type}, SDP Length: ${signalMessage.sdp?.length ?: 0}")

                // FIX: Filter out messages sent by this child to prevent echo
                if (signalMessage.sender == "child") {
                    Log.d(TAG, "🔄 Ignoring message sent by this child (echo prevention)")
                    return@MessageListener
                }

                listener?.onSignalMessageReceived(signalMessage)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to parse message: ${message.data}", e)
            }
        }

        try {
            channel?.subscribe("OFFER", messageListener)
            channel?.subscribe("ANSWER", messageListener)
            channel?.subscribe("ICE_CANDIDATE", messageListener)
            channel?.subscribe("CAMERA_SWITCH", messageListener)
            channel?.subscribe("TORCH_TOGGLE", messageListener)
            channel?.subscribe("CONTROL_CONFIRMATION", messageListener)
            listener?.onConnectionEstablished()
            Log.d(TAG, "Successfully subscribed to OFFER, ANSWER, ICE_CANDIDATE, CAMERA_SWITCH, TORCH_TOGGLE, and CONTROL_CONFIRMATION events.")
        } catch (e: AblyException) {
            Log.e(TAG, "Error subscribing to channel", e)
        }
    }

    fun sendMessage(message: SignalMessage) {
        Log.d(TAG, "📤 AblySignalManager.sendMessage called with type: ${message.type}")

        // FIX: Queue message if not connected
        if (!isConnected || channel == null) {
            Log.w(TAG, "⚠️ Not connected to Ably. Queueing message: ${message.type}")
            queueMessage(message)
            return
        }

        // FIX: Clean up old queued messages before sending new ones
        cleanupOldQueuedMessages()

        try {
            val jsonMessage = gson.toJson(message)
            val messageSize = jsonMessage.toByteArray().size

            // FIX: Check message size limit (Ably limit is 65KB)
            if (messageSize > 65000) {
                Log.w(TAG, "Message size ($messageSize bytes) exceeds Ably limit. Attempting to compress or split.")
                // For now, try to send anyway but log the issue
                // TODO: Implement message compression or chunking
            }

            Log.d(TAG, "📡 Publishing message to Ably: ${message.type}, Size: $messageSize bytes")
            channel?.publish(message.type, jsonMessage, object: CompletionListener {
                override fun onSuccess() {
                    Log.d(TAG, "✅ Ably message sent successfully: ${message.type}")
                }
                override fun onError(reason: ErrorInfo?) {
                    Log.e(TAG, "❌ Failed to send Ably message: ${reason?.message}")

                    // FIX: Queue message on failure for retry
                    if (shouldRetryMessage(reason)) {
                        Log.d(TAG, "🔄 Queueing failed message for retry: ${message.type}")
                        queueMessage(message)
                    }

                    if (reason?.message?.contains("Maximum message length exceeded") == true) {
                        Log.e(TAG, "Message too large, implementing fallback...")
                        // TODO: Implement message chunking
                    }
                }
            })
        } catch (e: AblyException) {
            Log.e(TAG, "❌ Failed to send message: ${e.errorInfo.message}", e)

            // FIX: Queue message on exception
            if (shouldRetryMessage(null)) {
                queueMessage(message)
            }
        }
    }

    // FIX: Add message queuing functionality
    private fun queueMessage(message: SignalMessage) {
        val queuedMessage = QueuedMessage(message)
        messageQueue.add(queuedMessage)

        // FIX: Limit queue size to prevent memory issues
        if (messageQueue.size > 50) {
            Log.w(TAG, "⚠️ Message queue full, removing oldest message")
            messageQueue.removeAt(0)
        }

        Log.d(TAG, "📋 Message queued: ${message.type} (queue size: ${messageQueue.size})")
    }

    // FIX: Process queued messages when connection is restored
    private fun processQueuedMessages() {
        if (messageQueue.isEmpty()) {
            Log.d(TAG, "📋 No queued messages to process")
            return
        }

        Log.d(TAG, "📋 Processing ${messageQueue.size} queued messages")

        // FIX: Process messages in order, but with a small delay between each
        val messagesToProcess = messageQueue.toList() // Create a copy
        messageQueue.clear()

        messagesToProcess.forEachIndexed { index, queuedMessage ->
            retryHandler.postDelayed({
                if (isConnected && channel != null) {
                    Log.d(TAG, "📤 Sending queued message: ${queuedMessage.message.type}")
                    sendMessage(queuedMessage.message)
                } else {
                    Log.w(TAG, "⚠️ Connection lost while processing queue, re-queuing message")
                    messageQueue.add(queuedMessage)
                }
            }, index * 100L) // 100ms delay between messages
        }
    }

    // FIX: Clean up old queued messages to prevent memory buildup
    private fun cleanupOldQueuedMessages() {
        val currentTime = System.currentTimeMillis()
        val maxAge = 5 * 60 * 1000L // 5 minutes

        messageQueue.removeAll { queuedMessage ->
            val age = currentTime - queuedMessage.timestamp
            if (age > maxAge) {
                Log.w(TAG, "🗑️ Removing old queued message: ${queuedMessage.message.type} (age: ${age}ms)")
                true
            } else {
                false
            }
        }
    }

    // FIX: Determine if a message should be retried
    private fun shouldRetryMessage(reason: ErrorInfo?): Boolean {
        // Don't retry if it's a permanent error
        if (reason?.message?.contains("auth", ignoreCase = true) == true) {
            return false // Authentication errors shouldn't be retried
        }

        if (reason?.message?.contains("forbidden", ignoreCase = true) == true) {
            return false // Permission errors shouldn't be retried
        }

        // Retry for network errors, connection issues, etc.
        return true
    }

    fun disconnect() {
        if (isConnected || isConnecting) {
            channel?.unsubscribe()
            channel?.detach()
            ably?.close()
            isConnected = false
            isConnecting = false // FIX: Reset connecting state
            channel = null
            ably = null
            Thread.sleep(500) // Add delay for cleanup
            Log.d(TAG, "Ably disconnected.")
        }
    }
}
