// parent/webrtc/WebRTCManager.kt

package com.bannigaurd.parent.webrtc

import android.content.Context
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.webrtc.*

// FIX: यह इंटरफ़ेस अब Fragments के साथ संचार करने के लिए है
interface WebRTCConnectionListener {
    fun onConnectionStateChanged(state: PeerConnection.IceConnectionState)
    fun onRemoteAudioTrack(track: AudioTrack)
    fun onRemoteVideoTrack(track: VideoTrack)
}

// ✅ FIX: Add connection state management enum
enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED
}

class WebRTCManager(
    private val context: Context,
    private val signalManager: AblySignalManager,
    private val isVideoCall: Boolean
) : SignalListener {

    private val accountSid = "AC92cf55a087bc392a185be0b4cf24dedd"
    private val authToken = "e2eab2934fc4068311c9577c7ddc0ef8"
    private val lock = Any() // ✅ FIX: Synchronization lock to prevent race conditions

    private val eglBase: EglBase = EglBase.create()
    val eglContext: EglBase.Context = eglBase.eglBaseContext
    var connectionListener: WebRTCConnectionListener? = null

    private var parentWebRTCCore: ParentWebRTCCore? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val TAG = "WebRTCManager_Parent"
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    // Enhanced state management
    private var isConnected = false
    private var isConnecting = false
    private var mediaTracksAdded = false
    private var currentCameraId: String? = null
    private var isTorchOn = false
    private var connectionStartTime: Long = 0
    private var lastConnectionAttempt: Long = 0
    private val CONNECTION_TIMEOUT = 30000L // 30 seconds
    private val RECONNECT_DELAY = 2000L // 2 seconds

    private fun hasRequiredPermissions(): Boolean {
        val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // Check camera permission only for video calls
        val cameraGranted = if (isVideoCall) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed for audio-only calls
        }
        
        val result = audioGranted && cameraGranted
        Log.d(TAG, "Permission check: RECORD_AUDIO=$audioGranted, CAMERA=$cameraGranted, result=$result")
        return result
    }

    fun startConnection(apiKey: String, deviceId: String) {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "❌ Missing required permissions for connection")
            connectionListener?.onConnectionStateChanged(PeerConnection.IceConnectionState.FAILED)
            return
        }
        
        // Prevent rapid reconnection attempts
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastConnectionAttempt < 3000) {
            Log.w(TAG, "⚠️ Connection attempt too soon after previous attempt, delaying...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startConnection(apiKey, deviceId)
            }, 3000)
            return
        }
        lastConnectionAttempt = currentTime

        val channelName = "$deviceId-v2"
        Log.d(TAG, "🔗 Parent starting connection for device: $deviceId on channel: $channelName")
        Log.d(TAG, "Preparing AudioManager for the call.")

        // Configure audio manager for WebRTC audio playback with enhanced settings
        configureAudioManager()
        
        Log.d(TAG, "🎵 AudioManager configured for ${if (isVideoCall) "video call" else "audio call"}")
        Log.d(TAG, "   - Mode: ${audioManager.mode} (${getAudioModeName(audioManager.mode)})")
        Log.d(TAG, "   - Speakerphone: ${audioManager.isSpeakerphoneOn}")
        Log.d(TAG, "   - Voice call volume: ${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}")
        Log.d(TAG, "   - Microphone muted: ${audioManager.isMicrophoneMute}")

        signalManager.setSignalListener(this)

        // ✅ FIX: कनेक्शन शुरू करने से पहले हमेशा पुरानी स्टेट को रीसेट करें।
        Log.d(TAG, "🔄 Resetting connection state for new connection")
        resetConnectionState()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            CoroutineScope(Dispatchers.IO).launch {
                // ✅ FIX: यहां channelName को पैरामीटर के रूप में पास करें
                fetchIceServersAndConnect(apiKey, channelName)
            }
        }, 500)
    }

    private suspend fun fetchIceServersAndConnect(apiKey: String, channelName: String) {
        Log.d(TAG, "🔄 Starting ICE servers configuration...")

        // Always include STUN servers as fallback
        val stunServers = mutableListOf<PeerConnection.IceServer>()
        stunServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        stunServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
        stunServers.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer())
        stunServers.add(PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer())
        stunServers.add(PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer())
        Log.d(TAG, "✅ STUN servers configured: ${stunServers.size} servers")

        // Try to fetch TURN servers from Twilio with timeout and retry
        val turnServers = fetchTurnServersWithRetry()

        // Combine STUN and TURN servers
        val allServers = mutableListOf<PeerConnection.IceServer>()
        allServers.addAll(stunServers)
        allServers.addAll(turnServers)

        this.iceServers = allServers

        Log.d(TAG, "📊 Final ICE configuration:")
        Log.d(TAG, "   - STUN servers: ${stunServers.size}")
        Log.d(TAG, "   - TURN servers: ${turnServers.size}")
        Log.d(TAG, "   - Total servers: ${allServers.size}")

        if (turnServers.isNotEmpty()) {
            Log.d(TAG, "🎉 TURN servers successfully configured! Better connectivity expected.")
        } else {
            Log.w(TAG, "⚠️ Only STUN servers available. May have connectivity issues with NAT/firewalls.")
        }

        // Connect to signaling server immediately without delay
        CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "🚀 Connecting to signaling server...")
            signalManager.connect(apiKey, channelName)
        }
    }

    private suspend fun fetchTurnServersWithRetry(maxRetries: Int = 2): List<PeerConnection.IceServer> {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                Log.d(TAG, "🔄 TURN server fetch attempt $attempt/$maxRetries")

                // Use shorter timeout for faster fallback
                val authString = "$accountSid:$authToken"
                val encodedAuth = Base64.encodeToString(authString.toByteArray(), Base64.NO_WRAP)
                val authHeader = "Basic $encodedAuth"

                val response = withTimeout(5000) { // Reduced from 8 seconds to 5 seconds
                    RetrofitClient.instance.getIceServers(authHeader, accountSid)
                }

                if (response.isSuccessful) {
                    val iceServerResponse = response.body()
                    if (iceServerResponse != null && iceServerResponse.ice_servers.isNotEmpty()) {
                        Log.d(TAG, "✅ Twilio API response received. Processing ${iceServerResponse.ice_servers.size} servers...")

                        val turnServers = iceServerResponse.ice_servers.mapNotNull { server ->
                            val serverUrl = server.url
                            if (serverUrl.isNotEmpty()) {
                                Log.d(TAG, "🔧 Processing TURN server: $serverUrl")
                                try {
                                    PeerConnection.IceServer.builder(serverUrl)
                                        .setUsername(server.username ?: "")
                                        .setPassword(server.credential ?: "")
                                        .createIceServer()
                                } catch (e: Exception) {
                                    Log.w(TAG, "⚠️ Failed to create TURN server: $serverUrl")
                                    null
                                }
                            } else null
                        }

                        if (turnServers.isNotEmpty()) {
                            Log.d(TAG, "✅ Successfully configured ${turnServers.size} TURN servers on attempt $attempt")
                            return turnServers
                        }
                    } else {
                        Log.w(TAG, "⚠️ Twilio API returned empty or null response on attempt $attempt")
                    }
                } else {
                    Log.w(TAG, "⚠️ Twilio API call failed on attempt $attempt. Code: ${response.code()}, Message: ${response.message()}")
                }

            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "⚠️ TURN server fetch failed on attempt $attempt: ${e.message}")

                // Don't wait between retries for faster connection
                if (attempt < maxRetries) {
                    delay(500) // Reduced delay
                }
            }
        }

        // All attempts failed
        Log.e(TAG, "❌ All TURN server fetch attempts failed. Last error: ${lastException?.message}")
        Log.w(TAG, "⚠️ Proceeding with STUN servers only - connection may be slower")

        return emptyList()
    }

    override fun onConnectionEstablished() {
        Log.d(TAG, "🎉 Parent: Signaling connected successfully! Creating PeerConnection as ANSWERER.")
        Log.d(TAG, "📡 Ready to receive messages from kid device")
        isConnecting = true
        connectionStartTime = System.currentTimeMillis()

        // FIX: Clean up any existing WebRTC core before creating new one
        cleanupExistingWebRTCCore()

        parentWebRTCCore = ParentWebRTCCore(context, eglContext, onSignalToSend = { message ->
            signalManager.sendMessage(message)
        }, onConnectionStateChanged = { state ->
            connectionListener?.onConnectionStateChanged(state)
        }, onTrackReceived = { track ->
            if (track is AudioTrack) {
                connectionListener?.onRemoteAudioTrack(track)
            } else if (track is VideoTrack) {
                connectionListener?.onRemoteVideoTrack(track)
            }
        })
        parentWebRTCCore?.createPeerConnection(iceServers)
        parentWebRTCCore?.addRecvTransceivers(isVideoCall)
        // Parent does NOT create offer - only waits for kid's offer
        Log.d(TAG, "Parent: Ready to receive kid's OFFER...")

        // ✅ FIX: Add timeout for OFFER reception with auto-reconnect
        startOfferTimeoutTimer()
    }

    // CRITICAL FIX: Add timeout timer to detect when child isn't responding
    private var offerTimeoutHandler: android.os.Handler? = null
    private var offerTimeoutRunnable: Runnable? = null
    
    private fun startOfferTimeoutTimer() {
        cancelOfferTimeoutTimer() // Cancel any existing timer
        
        offerTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        offerTimeoutRunnable = Runnable {
            if (parentWebRTCCore != null && !isConnected) {
                Log.w(TAG, "⚠️ TIMEOUT: No OFFER received from child device after 15 seconds")
                Log.w(TAG, "💡 Possible issues: Child app not running, commands not reaching child, or network problems")
                
                // Show user-friendly error message
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    connectionListener?.onConnectionStateChanged(PeerConnection.IceConnectionState.FAILED)
                }
                
                // Force cleanup after timeout
                forceCompleteCleanup()
                
                // Try to reconnect automatically after timeout
                val apiKey = signalManager.getLastApiKey()
                val channelName = signalManager.getCurrentChannelName()
                if (apiKey != null && channelName != null && channelName.endsWith("-v2")) {
                    val deviceId = channelName.replace("-v2", "")
                    Log.d(TAG, "🔄 Attempting automatic reconnection after timeout")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startConnection(apiKey, deviceId)
                    }, 3000)
                }
            }
        }
        
        offerTimeoutHandler?.postDelayed(offerTimeoutRunnable!!, 15000) // 15 seconds
        Log.d(TAG, "⏰ OFFER timeout timer started (15s)")
    }
    
    private fun cancelOfferTimeoutTimer() {
        offerTimeoutRunnable?.let { runnable ->
            offerTimeoutHandler?.removeCallbacks(runnable)
        }
        offerTimeoutHandler = null
        offerTimeoutRunnable = null
        Log.d(TAG, "✅ OFFER timeout timer cancelled")
    }

    // FIX: Method to clean up existing WebRTC core
    private fun cleanupExistingWebRTCCore() {
        parentWebRTCCore?.let { core ->
            try {
                Log.d(TAG, "🧹 Cleaning up existing WebRTC core...")
                core.close()
                parentWebRTCCore = null
                Log.d(TAG, "✅ Existing WebRTC core cleaned up")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error cleaning up existing WebRTC core: ${e.message}")
            }
        }
    }

    override fun onSignalMessageReceived(message: SignalMessage) {
        Log.d(TAG, "🎯 Parent signal received: ${message.type}")
        Log.d(TAG, "📦 Message details:")
        Log.d(TAG, "   - Type: ${message.type}")
        Log.d(TAG, "   - SDP length: ${message.sdp?.length ?: 0}")
        Log.d(TAG, "   - Has candidate: ${message.candidate != null}")

        // FIX: Always handle OFFER messages, even if WebRTC core needs to be recreated
        when (message.type) {
            "OFFER" -> {
                Log.d(TAG, "🎯 Parent received OFFER. Handling it...")
                handleOfferMessage(message)
            }
            "ANSWER" -> {
                // Parent should not receive ANSWER - this is likely an echo
                Log.w(TAG, "⚠️ Parent received unexpected ANSWER message - ignoring (possible echo)")
            }
            "ICE_CANDIDATE" -> {
                parentWebRTCCore?.let { core ->
                    message.candidate?.let {
                        Log.d(TAG, "Parent adding received ICE Candidate.")
                        core.addIceCandidate(it.toIceCandidate())
                    }
                } ?: Log.w(TAG, "parentWebRTCCore not initialized, ignoring ICE_CANDIDATE")
            }
            "CONTROL_CONFIRMATION" -> {
                Log.d(TAG, "Parent received control confirmation: ${message.command} - ${message.status}")
                handleControlConfirmation(message)
            }
        }
    }

    // FIX: Separate method to handle OFFER messages with proper error handling
    private fun handleOfferMessage(message: SignalMessage) {
        Log.d(TAG, "🎯 STARTING OFFER HANDLING PROCESS")
        
        // CRITICAL FIX: Cancel timeout timer when OFFER is received
        cancelOfferTimeoutTimer()
        
        try {
            message.sdp?.let { sdp ->
                Log.d(TAG, "📨 Received SDP from kid: ${sdp.take(100)}...")

                // FIX: Always ensure WebRTC core exists and is ready
                if (parentWebRTCCore == null || parentWebRTCCore?.isDisposed() == true) {
                    Log.d(TAG, "🔄 WebRTC core not ready, creating new one for OFFER handling")
                    createWebRTCCoreForOffer()

                    // Wait a bit for WebRTC core to initialize
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        handleOfferWithCore(sdp)
                    }, 500)
                } else {
                    // WebRTC core exists and is ready
                    handleOfferWithCore(sdp)
                }
            } ?: Log.e(TAG, "❌ OFFER message has no SDP!")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception in handleOfferMessage: ${e.message}", e)
            // Try to recreate WebRTC core if it failed
            if (e.message?.contains("disposed") == true || e.message?.contains("null") == true) {
                Log.d(TAG, "🔄 PeerConnectionFactory disposed or null, recreating WebRTC core")
                recreateWebRTCCore()
                // Retry handling the OFFER after recreation
                message.sdp?.let { sdp ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        handleOfferWithCore(sdp)
                    }, 1000)
                }
            }
        }
    }

    // FIX: Separate method to handle OFFER with existing core
    // Add configureAudioManager method for enhanced audio settings
    private fun configureAudioManager() {
        try {
            Log.d(TAG, "Configuring AudioManager for ${if (isVideoCall) "video call" else "audio call"}")
            
            // Force MODE_IN_COMMUNICATION for better audio quality in calls
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            
            // Set speakerphone based on call type (video calls use speaker, audio calls use earpiece)
            audioManager.isSpeakerphoneOn = isVideoCall
            
            // Ensure voice call volume is at least 70% of max for better audibility
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val targetVolume = (maxVolume * 0.7).toInt()
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            
            if (currentVolume < targetVolume) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    targetVolume,
                    0
                )
            }
            
            // Ensure microphone is unmuted
            audioManager.isMicrophoneMute = false
            
            Log.d(TAG, "🎵 AudioManager configured successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error configuring AudioManager: ${e.message}")
            // Try recovery with basic settings
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = isVideoCall
                Log.d(TAG, "⚠️ Applied fallback audio configuration")
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Critical audio configuration failure: ${e2.message}")
            }
        }
    }
    
    private fun getAudioModeName(mode: Int): String {
        return when (mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            else -> "UNKNOWN_MODE"
        }
    }
    
    // Add proper audio manager reset method
    private fun resetAudioManager() {
        try {
            Log.d(TAG, "🔄 Resetting AudioManager to normal state")
            
            // Reset to normal mode
            audioManager.mode = AudioManager.MODE_NORMAL
            
            // Turn off speakerphone
            audioManager.isSpeakerphoneOn = false
            
            // Ensure microphone is unmuted for other apps
            audioManager.isMicrophoneMute = false
            
            // Reset volume to a reasonable level
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val defaultVolume = (maxVolume * 0.5).toInt() // 50% volume
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                defaultVolume,
                0
            )
            
            Log.d(TAG, "✅ AudioManager reset successfully")
            Log.d(TAG, "   - Mode: ${audioManager.mode} (${getAudioModeName(audioManager.mode)})")
            Log.d(TAG, "   - Speakerphone: ${audioManager.isSpeakerphoneOn}")
            Log.d(TAG, "   - Microphone muted: ${audioManager.isMicrophoneMute}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error resetting AudioManager: ${e.message}")
            try {
                // Minimal fallback reset
                audioManager.mode = AudioManager.MODE_NORMAL
                Log.d(TAG, "⚠️ Applied minimal AudioManager reset")
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Critical AudioManager reset failure: ${e2.message}")
            }
        }
    }
            
    private fun handleOfferWithCore(sdp: String) {
        try {
            parentWebRTCCore?.let { core ->
                if (!core.isDisposed()) {
                    Log.d(TAG, "📡 Calling core.handleOffer()")
                    // Handle the OFFER
                    core.handleOffer(sdp)
                    Log.d(TAG, "✅ handleOffer() called successfully")
                } else {
                    Log.w(TAG, "❌ WebRTC core is disposed, cannot handle OFFER")
                }
            } ?: Log.e(TAG, "❌ parentWebRTCCore is null!")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception in handleOfferWithCore: ${e.message}", e)
        }
    }

    // FIX: Method to create WebRTC core specifically for handling OFFER
    private fun createWebRTCCoreForOffer() {
        try {
            Log.d(TAG, "Creating WebRTC core for OFFER handling")
            parentWebRTCCore = ParentWebRTCCore(context, eglContext, onSignalToSend = { message ->
                signalManager.sendMessage(message)
            }, onConnectionStateChanged = { state ->
                connectionListener?.onConnectionStateChanged(state)
            }, onTrackReceived = { track ->
                if (track is AudioTrack) {
                    connectionListener?.onRemoteAudioTrack(track)
                } else if (track is VideoTrack) {
                    connectionListener?.onRemoteVideoTrack(track)
                }
            })

            // Create peer connection with current ICE servers
            parentWebRTCCore?.createPeerConnection(iceServers)
            parentWebRTCCore?.addRecvTransceivers(isVideoCall)

            Log.d(TAG, "WebRTC core created successfully for OFFER handling")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebRTC core for OFFER: ${e.message}", e)
        }
    }

    // FIX: Method to recreate WebRTC core when disposed
    private fun recreateWebRTCCore() {
        try {
            Log.d(TAG, "Recreating WebRTC core after disposal")

            // Clean up existing core if it exists
            parentWebRTCCore?.let { core ->
                try {
                    core.close()
                    parentWebRTCCore = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing existing WebRTC core: ${e.message}")
                }
            }

            // Create new core
            createWebRTCCoreForOffer()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate WebRTC core: ${e.message}", e)
        }
    }

    private fun handleControlConfirmation(message: SignalMessage) {
        val command = message.command ?: return
        val status = message.status ?: return
        val details = message.details

        when (command) {
            "switchCamera" -> {
                if (status == "success") {
                    val cameraInfo = when (details) {
                        "front" -> "front camera"
                        "back" -> "back camera"
                        else -> "unknown camera"
                    }
                    Log.i(TAG, "✅ Camera switched to $cameraInfo")
                } else {
                    val errorInfo = when (details) {
                        "no_permission" -> " (no camera permission)"
                        "no_capturer" -> " (no camera capturer)"
                        "switch_error" -> " (switch error)"
                        "exception" -> " (exception occurred)"
                        else -> ""
                    }
                    Log.w(TAG, "❌ Camera switch failed$errorInfo")
                }
            }
            "toggleTorch" -> {
                if (status == "success") {
                    val torchState = when (details) {
                        "on" -> "ON"
                        "off" -> "OFF"
                        else -> "toggled"
                    }
                    Log.i(TAG, "✅ Torch $torchState")
                } else {
                    val errorInfo = when (details) {
                        "no_permission" -> " (no camera permission)"
                        "not_supported" -> " (torch not supported on this device)"
                        "error" -> " (torch error)"
                        else -> ""
                    }
                    Log.w(TAG, "❌ Torch toggle failed$errorInfo")
                }
            }
        }
    }

    override fun onConnectionError(error: String) {
        Log.e(TAG, "Signaling connection error: $error")
    }

    fun setRemoteVideoSink(sink: VideoSink?) {
        // यह लॉजिक अब सीधे WebRTCManager में नहीं, बल्कि Fragment में रहेगा।
        // Fragment से ही `onRemoteVideoTrack` को हैंडल किया जाएगा।
    }

    fun toggleAudio(mute: Boolean) {
        // यह लॉजिक अब सीधे WebRTCManager में नहीं, बल्कि Fragment में रहेगा।
    }

    fun sendControlCommand(command: Map<String, Any>) { }

    // Expose a helper to dump inbound video stats from the parent peer connection
    fun dumpInboundVideoStats() {
        parentWebRTCCore?.dumpInboundVideoStats() ?: Log.w(TAG, "parentWebRTCCore not initialized; cannot dump inbound video stats")
    }

    // FIX: Add missing cleanup methods
    private fun cleanupMediaTracks() {
        try {
            Log.d(TAG, "🧹 Cleaning up media tracks")
            // Parent app doesn't create media tracks, so this is mostly a placeholder
            Log.d(TAG, "✅ Media tracks cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cleaning up media tracks: ${e.message}", e)
        }
    }

    // This method has been replaced by the more comprehensive resetAudioManager method below
    // See line ~450

    fun disconnect() {
        synchronized(lock) {
            Log.d(TAG, "🔒 Disconnecting WebRTCManager with synchronization.")
            try {
                // Cancel any pending timers first
                cancelOfferTimeoutTimer()
                
                // Reset connection state variables
                resetConnectionState()
                
                // Clean up media tracks
                cleanupMediaTracks()
                
                // Disconnect signal manager
                signalManager.disconnect()
                
                // Reset audio manager to normal mode
                resetAudioManager()
                
                // Close and nullify WebRTC core
                parentWebRTCCore?.close()
                parentWebRTCCore = null
                
                // Request garbage collection
                System.gc()
                
                Log.d(TAG, "✅ WebRTCManager fully disconnected and cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during disconnect: ${e.message}", e)
                
                // Attempt force cleanup as fallback
                try {
                    forceCompleteCleanup()
                    Log.d(TAG, "⚠️ Applied force cleanup after disconnect error")
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ Critical failure during force cleanup: ${e2.message}")
                }
            }
        }
    }

    // FIX: Add force cleanup method to ensure complete cleanup
    private fun forceCleanup() {
        try {
            Log.d(TAG, "🧹 Force cleanup of remaining resources")

            // Cancel any pending timers first
            cancelOfferTimeoutTimer()
            
            // Force disconnect signaling if still connected
            try {
                if (AblySignalManager.isConnected()) {
                    AblySignalManager.disconnect()
                    Log.d(TAG, "✅ Forced signaling disconnect")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during forced signaling disconnect: ${e.message}")
            }
            
            // Reset audio manager
            try {
                resetAudioManager()
                Log.d(TAG, "✅ Audio manager reset during force cleanup")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error resetting audio manager during force cleanup: ${e.message}")
            }
            
            // Clean up WebRTC core if exists
            try {
                parentWebRTCCore?.close()
                parentWebRTCCore = null
                Log.d(TAG, "✅ WebRTC core closed during force cleanup")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error closing WebRTC core during force cleanup: ${e.message}")
            }

            // Reset all state variables to ensure clean slate
            isConnected = false
            isConnecting = false
            mediaTracksAdded = false
            currentCameraId = null
            isTorchOn = false
            
            // Request garbage collection
            System.gc()

            Log.d(TAG, "✅ Force cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during force cleanup: ${e.message}", e)
        }
    }

    // FIX: Add method to check if already connected
    fun isAlreadyConnected(): Boolean {
        return isConnected && parentWebRTCCore != null && (parentWebRTCCore?.isDisposed() == false)
    }

    // FIX: Add method to prevent multiple connection attempts
    fun canStartNewConnection(): Boolean {
        if (isAlreadyConnected()) {
            Log.w(TAG, "⚠️ Already connected! Cannot start new connection")
            return false
        }

        parentWebRTCCore?.let { core ->
            if (!core.isDisposed()) {
                Log.w(TAG, "⚠️ WebRTC core exists but not marked as connected. Cleaning up first...")
                try {
                    core.close()
                    parentWebRTCCore = null
                    Log.d(TAG, "✅ Cleaned up existing WebRTC core")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error cleaning up existing WebRTC core: ${e.message}")
                }
            }
        }

        return true
    }

    // ✅ FIX: कनेक्शन की स्टेट को रीसेट करने के लिए यह नया मेथड जोड़ें।
    private fun resetConnectionState() {
        Log.d(TAG, "🔄 Resetting connection state for new connection")
        isConnected = false
        isConnecting = false

        try {
            AblySignalManager.forceCompleteReset()
            Log.d(TAG, "✅ Signaling force reset completed during state reset")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error during signaling force reset: ${e.message}")
        }

        cleanupExistingWebRTCCore()
        
        // Reset audio manager to normal mode with proper cleanup
        resetAudioManager()
        
        // Request garbage collection to free up memory
        System.gc()
        
        Log.d(TAG, "✅ Connection state reset")
    }

    // ✅ FIX: Enhanced force complete cleanup for INSTANT disconnect
    fun forceCompleteCleanup() {
        Log.d(TAG, "🚨 FORCE COMPLETE CLEANUP INITIATED - INSTANT MODE")

        try {
            // CRITICAL FIX: Cancel timeout timer immediately
            cancelOfferTimeoutTimer()
            
            // Reset audio manager to normal mode
            resetAudioManager()
            
            // Clean up media tracks
            try {
                cleanupMediaTracks()
                Log.d(TAG, "✅ Media tracks cleaned up during force complete cleanup")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error cleaning up media tracks: ${e.message}")
            }
            
            // Force disconnect signaling
            try {
                signalManager.disconnect()
                Log.d(TAG, "✅ Signal manager disconnected during force complete cleanup")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error disconnecting signal manager: ${e.message}")
            }
            
            // Close WebRTC core
            try {
                parentWebRTCCore?.close()
                parentWebRTCCore = null
                Log.d(TAG, "✅ WebRTC core closed during force complete cleanup")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error closing WebRTC core: ${e.message}")
            }
            
            // ✅ FIX: Immediately reset all state variables
            isConnected = false
            isConnecting = false
            mediaTracksAdded = false
            currentCameraId = null
            isTorchOn = false
            connectionStartTime = 0
            lastConnectionAttempt = 0
            
            // Request garbage collection
            System.gc()
            
            Log.d(TAG, "✅ Force complete cleanup finished successfully")

            // ✅ FIX: Force disconnect signaling FIRST and IMMEDIATELY
            try {
                AblySignalManager.forceCompleteReset()
                Log.d(TAG, "✅ Forced signaling complete reset immediately")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during forced signaling reset: ${e.message}")
            }

            // ✅ FIX: Force cleanup WebRTC core immediately
            try {
                cleanupExistingWebRTCCore()
                Log.d(TAG, "✅ WebRTC core force cleaned up immediately")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during WebRTC core cleanup: ${e.message}")
            }

            // ✅ FIX: Reset audio manager immediately
            try {
                resetAudioManager()
                Log.d(TAG, "✅ Audio manager reset immediately")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during audio manager reset: ${e.message}")
            }

            // ✅ FIX: Force garbage collection hint
            try {
                System.gc()
                Log.d(TAG, "✅ Garbage collection requested")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during garbage collection: ${e.message}")
            }

            Log.d(TAG, "✅ Force complete cleanup finished instantly")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during force complete cleanup: ${e.message}", e)
        }
    }
}
