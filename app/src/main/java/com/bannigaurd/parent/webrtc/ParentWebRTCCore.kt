package com.bannigaurd.parent.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*

class ParentWebRTCCore(
    private val context: Context,
    private val eglContext: EglBase.Context,
    private val onSignalToSend: (SignalMessage) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.IceConnectionState) -> Unit,
    private val onTrackReceived: (MediaStreamTrack) -> Unit
) {
    private val TAG = "ParentWebRTCCore"
    private val lock = Any() // ✅ FIX: Synchronization lock to prevent race conditions

    private val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, false, true))
            .createPeerConnectionFactory()
    }

    private var peerConnection: PeerConnection? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    // ✅ FIX: पूरे SdpObserver को इस नए लॉजिक से बदलें
    private val sdpObserver = object : SdpObserver {
        private var answerSent = false // यह सुनिश्चित करेगा कि ANSWER सिर्फ एक बार भेजा जाए

        override fun onCreateSuccess(sessionDescription: SessionDescription) {
            Log.d(TAG, "🎯 Parent SDP created successfully: ${sessionDescription.type}")
            peerConnection?.setLocalDescription(this, sessionDescription)
        }

        override fun onSetSuccess() {
            Log.d(TAG, "✅ Parent SDP set successfully.")
            val remoteDesc = peerConnection?.remoteDescription
            val localDesc = peerConnection?.localDescription

            // अगर हमने Kid का OFFER सेट कर लिया है और अभी तक ANSWER नहीं भेजा है
            if (remoteDesc?.type == SessionDescription.Type.OFFER && localDesc == null && !answerSent) {
                Log.d(TAG, "🎯 Remote OFFER set, creating ANSWER...")
                createAnswer()
            }
            // अगर हमने अपना ANSWER सेट कर लिया है
            else if (remoteDesc?.type == SessionDescription.Type.OFFER && localDesc?.type == SessionDescription.Type.ANSWER) {
                if (!answerSent) {
                    answerSent = true
                    val message = SignalMessage(
                        type = localDesc.type.canonicalForm().uppercase(),
                        sdp = localDesc.description,
                        sender = "parent"
                    )
                    onSignalToSend(message)
                    Log.d(TAG, "✅ ANSWER sent to signaling server")
                }
            }
        }

        override fun onCreateFailure(error: String?) { Log.e(TAG, "Parent SDP onCreateFailure: $error") }
        override fun onSetFailure(error: String?) { Log.e(TAG, "Parent SDP onSetFailure: $error") }
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            onSignalToSend(SignalMessage("ICE_CANDIDATE", candidate = candidate.toModel(), sender = "parent"))
        }
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            Log.d(TAG, "Parent ICE State changed to: $newState")
            onConnectionStateChanged(newState)
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            Log.d(TAG, "🎥 Parent onTrack called with transceiver: $transceiver")
            transceiver?.receiver?.track()?.let { track ->
                Log.d(TAG, "🎥 Parent received track: ${track.id()}, kind: ${track.kind()}, enabled: ${track.enabled()}")
                Log.d(TAG, "🎥 Track state: ${track.state()}, kind: ${track.kind()}")

                // Additional logging for video tracks
                if (track is org.webrtc.VideoTrack) {
                    Log.d(TAG, "🎥 Video track details:")
                    Log.d(TAG, "   - ID: ${track.id()}")
                    Log.d(TAG, "   - Enabled: ${track.enabled()}")
                    Log.d(TAG, "   - State: ${track.state()}")
                }

                // Additional logging for audio tracks
                if (track is org.webrtc.AudioTrack) {
                    Log.d(TAG, "🎤 Audio track details:")
                    Log.d(TAG, "   - ID: ${track.id()}")
                    Log.d(TAG, "   - Enabled: ${track.enabled()}")
                    Log.d(TAG, "   - State: ${track.state()}")
                }

                onTrackReceived(track)
                Log.d(TAG, "✅ Track processing completed for: ${track.id()}")
            } ?: Log.w(TAG, "❌ Parent onTrack called but no track found in transceiver")
        }
        // अन्य सभी खाली ओवरराइड फ़ंक्शन
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(mediaStream: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        if (peerConnection != null) return
        this.iceServers = iceServers // Store iceServers for later use
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionObserver)
    }

    fun addRecvTransceivers(isVideoCall: Boolean) {
        // FIX: Add transceivers in the correct order to match SDP expectations
        // Always add audio transceiver first (m-line index 0)
        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        Log.d(TAG, "Audio transceiver added for receiving audio (m-line 0).")

        if (isVideoCall) {
            // Add video transceiver second (m-line index 1)
            peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            Log.d(TAG, "Video transceiver added for receiving video (m-line 1).")
        }
    }


    fun createOffer() {
        Log.d(TAG, "Creating OFFER...")
        peerConnection?.createOffer(sdpObserver, MediaConstraints())
    }

    private fun createAnswer() {
        Log.d(TAG, "🎯 Creating ANSWER for kid's OFFER...")
        try {
            peerConnection?.createAnswer(sdpObserver, MediaConstraints())
            Log.d(TAG, "✅ ANSWER creation initiated")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create ANSWER: ${e.message}", e)
        }
    }



    fun handleOffer(sdp: String) {
        Log.d(TAG, "🎯 Handling OFFER from kid device")
        Log.d(TAG, "📝 SDP length: ${sdp.length}")
        Log.d(TAG, "📨 SDP preview: ${sdp.take(200)}...")

        val currentState = peerConnection?.signalingState()
        Log.d(TAG, "📊 Current signaling state: $currentState")

        if (peerConnection == null) {
            Log.e(TAG, "❌ PeerConnection is null! Cannot handle OFFER")
            return
        }

        // FIX: Simplified OFFER handling - always try to set the remote description
        try {
            Log.d(TAG, "📡 Setting remote description (OFFER)")
            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
            peerConnection?.setRemoteDescription(sdpObserver, sessionDescription)
            Log.d(TAG, "✅ Remote OFFER set successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to set remote OFFER: ${e.message}", e)

            // If setting remote description fails, try to reset the connection
            Log.d(TAG, "🔄 Attempting to reset connection and retry OFFER handling")
            try {
                resetPeerConnectionForRenegotiation(sdp)
            } catch (resetException: Exception) {
                Log.e(TAG, "❌ Failed to reset connection for OFFER handling: ${resetException.message}", resetException)
            }
        }
    }

    fun handleAnswer(sdp: String) {
        Log.d(TAG, "🎯 Handling ANSWER from kid device")
        Log.d(TAG, "📝 SDP length: ${sdp.length}")

        val currentState = peerConnection?.signalingState()
        Log.d(TAG, "📊 Current signaling state before ANSWER: $currentState")

        // FIX: Only set ANSWER if we have a local offer and are waiting for answer
        if (currentState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            peerConnection?.setRemoteDescription(sdpObserver, sessionDescription)
            Log.d(TAG, "✅ ANSWER set successfully")
        } else {
            Log.e(TAG, "❌ Cannot set ANSWER in current state: $currentState")
            Log.e(TAG, "Expected state: HAVE_LOCAL_OFFER, Current state: $currentState")
            // Don't try to set the answer if we're not in the right state
            // This prevents the "Called in wrong state: stable" error
        }
    }

    private fun resetPeerConnectionForRenegotiation(sdp: String) {
        Log.d(TAG, "🔄 Resetting peer connection for renegotiation")

        try {
            // Close current peer connection
            peerConnection?.close()
            peerConnection = null

            // Create new peer connection with stored iceServers
            createNewPeerConnection(iceServers)

            // Set the remote offer on the new connection
            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
            peerConnection?.setRemoteDescription(sdpObserver, sessionDescription)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to reset peer connection for renegotiation: ${e.message}", e)
        }
    }

    private fun createNewPeerConnection(servers: List<PeerConnection.IceServer> = iceServers) {
        if (peerConnection != null) return

        val rtcConfig = PeerConnection.RTCConfiguration(servers)
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionObserver)

        // Re-add transceivers for the new connection
        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )

        Log.d(TAG, "✅ New peer connection created for renegotiation")
    }

    fun addIceCandidate(candidate: IceCandidate) {
        try {
            peerConnection?.addIceCandidate(candidate)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ICE candidate", e)
        }
    }

    // Dump inbound video stats to verify frames/bytes are arriving
    fun dumpInboundVideoStats() {
        val pc = peerConnection ?: run {
            Log.w(TAG, "dumpInboundVideoStats: peerConnection is null")
            return
        }
        pc.getStats { report ->
            try {
                for ((_, stat) in report.statsMap) {
                    if (stat.type == "inbound-rtp" && stat.members["kind"]?.toString() == "video") {
                        val framesDecoded = stat.members["framesDecoded"]
                        val bytesReceived = stat.members["bytesReceived"]
                        val frameWidth = stat.members["frameWidth"]
                        val frameHeight = stat.members["frameHeight"]
                        Log.d(
                            TAG,
                            "Stats inbound-rtp video: framesDecoded=$framesDecoded bytesReceived=$bytesReceived size=${frameWidth}x${frameHeight}"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while reading inbound video stats", e)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            Log.d(TAG, "🔒 Closing ParentWebRTCCore with synchronization...")
            try {
                // STEP 1: Close peer connection first
                if (peerConnection != null) {
                    try {
                        peerConnection?.close()
                        Log.d(TAG, "✅ PeerConnection closed")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Error closing PeerConnection: ${e.message}")
                    } finally {
                        peerConnection = null
                    }
                }

                // STEP 2: Dispose peer connection factory (only if not already disposed)
                try {
                    if (!isDisposed()) {
                        peerConnectionFactory.dispose()
                        Log.d(TAG, "✅ PeerConnectionFactory disposed")
                    } else {
                        Log.d(TAG, "ℹ️ PeerConnectionFactory already disposed")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ PeerConnectionFactory already disposed or error: ${e.message}")
                }

                // STEP 3: Release EGL base
                try {
                    eglBase.release()
                    Log.d(TAG, "✅ EGL base released")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error releasing EGL base: ${e.message}")
                }

                Log.d(TAG, "✅ ParentWebRTCCore closed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error closing ParentWebRTCCore: ${e.message}", e)
            }
        }
    }

    // FIX: Add method to check if disposed
    fun isDisposed(): Boolean {
        return try {
            // Try to access peerConnectionFactory to see if it's disposed
            peerConnectionFactory.hashCode()
            false
        } catch (e: Exception) {
            true
        }
    }

}
