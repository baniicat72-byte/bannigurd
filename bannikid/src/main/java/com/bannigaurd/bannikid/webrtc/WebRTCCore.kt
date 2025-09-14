package com.bannigaurd.bannikid.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class WebRTCCore(
    private val context: Context,
    private val onSignalToSend: (SignalMessage) -> Unit,
    private val connectionStateChangeListener: (PeerConnection.IceConnectionState) -> Unit
) {

    private val TAG = "WebRTCCore_Kid"
    var peerConnectionFactory: PeerConnectionFactory? = null
        private set
    var peerConnection: PeerConnection? = null
        private set
    var eglBase: EglBase? = null
        private set
    var isDisposed = false
        private set

    val eglBaseContext: EglBase.Context
        get() = eglBase?.eglBaseContext ?: throw IllegalStateException("EGL base not initialized")

    init {
        Log.d(TAG, "Initializing WebRTC Core")
        initializeWebRTC()
    }

    private fun initializeWebRTC() {
        try {
            // Initialize EGL
            eglBase = EglBase.create()
            Log.d(TAG, "EGL base initialized")

            // Initialize PeerConnectionFactory
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()

            PeerConnectionFactory.initialize(options)

            // Create audio device module
            val audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()

            // Create PeerConnectionFactory
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()

            Log.d(TAG, "PeerConnectionFactory initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC: ${e.message}", e)
        }
    }

    fun init(iceServers: List<PeerConnection.IceServer>) {
        try {
            // FIX: Only dispose if we have a PeerConnection, but preserve EGL base
            if (peerConnection != null) {
                Log.w(TAG, "PeerConnection already exists, disposing first")
                // Dispose PeerConnection only, keep EGL base and factory
                peerConnection?.close()
                peerConnection = null
                Log.d(TAG, "PeerConnection disposed, keeping EGL base and factory")
            }

            // FIX: Ensure EGL base is initialized if it was disposed
            if (eglBase == null) {
                Log.d(TAG, "EGL base is null, recreating...")
                eglBase = EglBase.create()
                Log.d(TAG, "EGL base recreated")
            }

            // FIX: Ensure PeerConnectionFactory is initialized if it was disposed
            if (peerConnectionFactory == null) {
                Log.d(TAG, "PeerConnectionFactory is null, recreating...")

                // Create audio device module
                val audioDeviceModule = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()

                // Create PeerConnectionFactory
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
                    .setAudioDeviceModule(audioDeviceModule)
                    .createPeerConnectionFactory()

                Log.d(TAG, "PeerConnectionFactory recreated successfully")
            }

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                keyType = PeerConnection.KeyType.ECDSA
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    Log.d(TAG, "Signaling state changed: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE connection state changed: $state")
                    connectionStateChangeListener(state)
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE connection receiving changed: $receiving")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "ICE gathering state changed: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "ICE candidate received: ${candidate.sdp}")
                    val iceCandidateModel = IceCandidateModel(
                        sdp = candidate.sdp,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        sdpMid = candidate.sdpMid
                    )
                    val signalMessage = SignalMessage(
                        type = "ICE_CANDIDATE",
                        candidate = iceCandidateModel
                    )
                    onSignalToSend(signalMessage)
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                    Log.d(TAG, "ICE candidates removed: ${candidates.size}")
                }

                override fun onAddStream(stream: MediaStream) {
                    Log.d(TAG, "Media stream added: ${stream.id}")
                }

                override fun onRemoveStream(stream: MediaStream) {
                    Log.d(TAG, "Media stream removed: ${stream.id}")
                }

                override fun onDataChannel(channel: DataChannel) {
                    Log.d(TAG, "Data channel created: ${channel.label()}")
                }

                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }

                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                    Log.d(TAG, "Track added: ${receiver.track()?.kind()}")
                }
            })

            Log.d(TAG, "PeerConnection initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PeerConnection: ${e.message}", e)
        }
    }

    fun createOffer() {
        try {
            Log.d(TAG, "Creating WebRTC offer")

            if (peerConnection == null) {
                Log.e(TAG, "❌ Cannot create offer: PeerConnection is null")
                return
            }

            // FIX: Ensure transceivers are added in correct order before creating offer
            ensureTransceiversInCorrectOrder()

            // FIX: Log current signaling state before creating offer
            val currentState = peerConnection?.signalingState()
            Log.d(TAG, "📊 Current signaling state before creating offer: $currentState")

            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    Log.d(TAG, "Offer created successfully")

                    // FIX: Log SDP details for debugging
                    Log.d(TAG, "📝 SDP created with ${sessionDescription.description.lines().count()} lines")
                    Log.d(TAG, "📝 SDP preview: ${sessionDescription.description.take(300)}...")

                    // FIX: Set local description and wait for success before sending
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set successfully")

                            // FIX: Double-check signaling state after setting local description
                            val stateAfterSet = peerConnection?.signalingState()
                            Log.d(TAG, "📊 Signaling state after setting local description: $stateAfterSet")

                            val signalMessage = SignalMessage(
                                type = "OFFER",
                                sdp = sessionDescription.description,
                                sender = "child"
                            )
                            Log.d(TAG, "📤 Sending OFFER through signaling callback...")
                            onSignalToSend(signalMessage)
                            Log.d(TAG, "✅ OFFER sent through signaling callback")
                        }

                        override fun onSetFailure(error: String) {
                            Log.e(TAG, "Failed to set local description: $error")
                            Log.e(TAG, "Current signaling state: ${peerConnection?.signalingState()}")
                        }

                        override fun onCreateSuccess(description: SessionDescription) {}
                        override fun onCreateFailure(error: String) {}
                    }, sessionDescription)
                }

                override fun onCreateFailure(error: String) {
                    Log.e(TAG, "Failed to create offer: $error")
                    Log.e(TAG, "Current signaling state: ${peerConnection?.signalingState()}")
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            })

        } catch (e: Exception) {
            Log.e(TAG, "Exception while creating offer: ${e.message}", e)
        }
    }

    // FIX: Ensure transceivers are added in correct order (audio first, then video)
    private fun ensureTransceiversInCorrectOrder() {
        try {
            val transceivers = peerConnection?.transceivers
            if (transceivers.isNullOrEmpty()) {
                Log.d(TAG, "No transceivers found, adding them in correct order")

                // Add audio transceiver first (m-line index 0)
                peerConnection?.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )
                Log.d(TAG, "Audio transceiver added for sending (m-line 0)")

                // Add video transceiver second (m-line index 1)
                peerConnection?.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )
                Log.d(TAG, "Video transceiver added for sending (m-line 1)")
            } else {
                Log.d(TAG, "Transceivers already exist: ${transceivers.size}")
                // Log existing transceivers for debugging
                transceivers.forEachIndexed { index, transceiver ->
                    Log.d(TAG, "Transceiver $index: ${transceiver.mediaType}, direction: ${transceiver.direction}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure transceivers order: ${e.message}", e)
        }
    }

    fun setRemoteAnswer(sdp: String) {
        try {
            Log.d(TAG, "Setting remote answer")

            // ✅ FIX: Check signaling state before setting remote description
            val currentState = peerConnection?.signalingState()
            Log.d(TAG, "📊 Current signaling state before setting remote answer: $currentState")

            // Only set remote answer if we're in HAVE_LOCAL_OFFER state
            if (currentState != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                Log.w(TAG, "⚠️ Cannot set remote answer in state: $currentState (expected: HAVE_LOCAL_OFFER)")
                Log.w(TAG, "   This might indicate a duplicate ANSWER or wrong signaling flow")

                // If we're already in STABLE state, the answer was probably already processed
                if (currentState == PeerConnection.SignalingState.STABLE) {
                    Log.d(TAG, "ℹ️ Already in STABLE state - ANSWER was likely already processed")
                    return
                }

                // For other states, log the issue but don't crash
                Log.e(TAG, "❌ Wrong signaling state for setting remote answer: $currentState")
                return
            }

            val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "✅ Remote description set successfully")
                    val stateAfterSet = peerConnection?.signalingState()
                    Log.d(TAG, "📊 Signaling state after setting remote answer: $stateAfterSet")
                }

                override fun onSetFailure(error: String) {
                    Log.e(TAG, "❌ Failed to set remote description: $error")
                    Log.e(TAG, "   Current signaling state: ${peerConnection?.signalingState()}")
                }

                override fun onCreateSuccess(description: SessionDescription) {}
                override fun onCreateFailure(error: String) {}
            }, sessionDescription)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception while setting remote answer: ${e.message}", e)
        }
    }

    fun addIceCandidate(candidate: IceCandidate) {
        try {
            Log.d(TAG, "Adding ICE candidate")
            peerConnection?.addIceCandidate(candidate)
        } catch (e: Exception) {
            Log.e(TAG, "Exception while adding ICE candidate: ${e.message}", e)
        }
    }

    fun addTrack(track: MediaStreamTrack): Boolean {
        try {
            Log.d(TAG, "Adding track: ${track.id()} (${track.kind()})")

            if (peerConnection == null) {
                Log.e(TAG, "❌ Cannot add track: PeerConnection is null")
                return false
            }

            if (!track.enabled()) {
                Log.w(TAG, "⚠️ Track ${track.id()} is disabled, enabling it")
                track.setEnabled(true)
            }

            val rtpSender = when (track) {
                is AudioTrack -> {
                    Log.d(TAG, "🎤 Adding audio track to PeerConnection")
                    peerConnection?.addTrack(track)
                }
                is VideoTrack -> {
                    Log.d(TAG, "📷 Adding video track to PeerConnection")
                    peerConnection?.addTrack(track)
                }
                else -> {
                    Log.w(TAG, "Unknown track type: ${track.javaClass.simpleName}")
                    null
                }
            }

            if (rtpSender != null) {
                Log.d(TAG, "✅ Track ${track.id()} added successfully to PeerConnection")
                Log.d(TAG, "   - Track kind: ${track.kind()}")
                Log.d(TAG, "   - Track enabled: ${track.enabled()}")
                Log.d(TAG, "   - Track state: ${track.state()}")
                Log.d(TAG, "   - RTP Sender ID: ${rtpSender.id()}")

                // Verify track is properly added
                verifyTrackAdded(track)
                return true
            } else {
                Log.e(TAG, "❌ Failed to add track ${track.id()} to PeerConnection")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception while adding track: ${e.message}", e)
            return false
        }
    }

    fun removeTrack(track: MediaStreamTrack) {
        try {
            Log.d(TAG, "Removing track: ${track.id()}")
            // Note: WebRTC doesn't have a direct removeTrack method
            // The track will be removed when the PeerConnection is recreated
        } catch (e: Exception) {
            Log.e(TAG, "Exception while removing track: ${e.message}", e)
        }
    }

    fun reset() {
        try {
            Log.d(TAG, "Resetting WebRTC core")
            peerConnection?.close()
            peerConnection = null
            Log.d(TAG, "WebRTC core reset successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Exception while resetting WebRTC core: ${e.message}", e)
        }
    }

    private fun verifyTrackAdded(track: MediaStreamTrack) {
        try {
            // Verify track is properly added by checking PeerConnection senders
            val senders = peerConnection?.senders
            val trackFound = senders?.any { sender ->
                sender.track()?.id() == track.id()
            } ?: false

            if (trackFound) {
                Log.d(TAG, "✅ Track ${track.id()} verified in PeerConnection senders")
            } else {
                Log.w(TAG, "⚠️ Track ${track.id()} not found in PeerConnection senders")
            }

            // Log all current senders for debugging
            senders?.forEach { sender ->
                Log.d(TAG, "   - Sender: ${sender.id()}, Track: ${sender.track()?.id()}, Kind: ${sender.track()?.kind()}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error verifying track addition: ${e.message}", e)
        }
    }

    fun dispose() {
        try {
            Log.d(TAG, "Disposing WebRTC core")
            peerConnection?.close()
            peerConnection = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            eglBase?.release()
            eglBase = null
            isDisposed = true
            Log.d(TAG, "WebRTC core disposed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Exception while disposing WebRTC core: ${e.message}", e)
        }
    }

    // ✅ FIX: Add public method to force close peer connection for instant disconnect
    fun forceClosePeerConnection() {
        try {
            Log.d(TAG, "Force closing peer connection")
            if (peerConnection != null) {
                peerConnection?.close()
                peerConnection = null
                Log.d(TAG, "✅ Peer connection force closed")
            } else {
                Log.d(TAG, "Peer connection was already null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error force closing peer connection: ${e.message}")
        }
    }


}
