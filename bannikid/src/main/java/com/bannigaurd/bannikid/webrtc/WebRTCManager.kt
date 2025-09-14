package com.bannigaurd.bannikid.webrtc

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bannigaurd.bannikid.StreamType
import com.bannigaurd.bannikid.webrtc.AblySignalManager
import com.bannigaurd.bannikid.webrtc.SignalListener
import com.bannigaurd.bannikid.webrtc.SignalMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.webrtc.*
import org.webrtc.CameraVideoCapturer.CameraSwitchHandler

/**
 * Singleton WebRTC Manager for kid device - prevents multiple instances
 * Clean and reliable WebRTC Manager for repeated audio/camera connections
 * Handles proper resource cleanup and safe reconnection
 */

class WebRTCManager private constructor(
    private val context: Context,
    private val streamType: StreamType,
    private val onSignalToSend: (SignalMessage) -> Unit,
    private val connectionStateChangeListener: (PeerConnection.IceConnectionState) -> Unit
) : SignalListener {

    companion object {
        private const val TAG = "WebRTCManager_Kid"
        private const val RECONNECT_DELAY = 2000L
        private const val CONNECTION_TIMEOUT = 30000L // 30 seconds

        @Volatile
        private var instance: WebRTCManager? = null
        private val instanceLock = Object()

        // ✅ FIX: Thread-safe instance creation with proper synchronization
        fun getInstance(
            context: Context,
            streamType: StreamType,
            onSignalToSend: (SignalMessage) -> Unit,
            connectionStateChangeListener: (PeerConnection.IceConnectionState) -> Unit
        ): WebRTCManager {
            return synchronized(instanceLock) {
                instance?.takeIf { !it.isDestroyed } ?: run {
                    Log.d(TAG, "🆕 Creating new WebRTCManager singleton instance")
                    val newInstance = WebRTCManager(context, streamType, onSignalToSend, connectionStateChangeListener)
                    instance = newInstance
                    Log.d(TAG, "✅ New WebRTCManager singleton instance created")
                    newInstance
                }
            }
        }

        fun getExistingInstance(): WebRTCManager? {
            return synchronized(instanceLock) {
                instance?.takeIf { !it.isDestroyed }
            }
        }

        // ✅ FIX: Enhanced destroy method with complete cleanup
        fun destroyInstance() {
            synchronized(instanceLock) {
                instance?.let { manager ->
                    Log.d(TAG, "🗑️ Force destroying WebRTCManager singleton instance")
                    try {
                        manager.forceDestroy()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Error during force destroy: ${e.message}")
                    }
                }
                instance = null
                Log.d(TAG, "✅ WebRTCManager singleton instance completely destroyed")
            }
        }
    }

    // ===== INSTANCE STATE =====
    @Volatile
    private var isDestroyed = false
    private var currentStreamType: StreamType = StreamType.NONE

    // ===== SIGNAL LISTENER IMPLEMENTATION =====

    override fun onSignalMessageReceived(message: SignalMessage) {
        Log.d(TAG, "📨 WebRTCManager received signal: ${message.type}")
        handleRemoteSignal(message)
    }

    override fun onConnectionEstablished() {
        Log.d(TAG, "✅ Signaling connected - starting ULTRA-FAST offerer flow")
        
        // CRITICAL FIX: IMMEDIATE OFFER creation for ultra-fast connection
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!isDestroyed && AblySignalManager.isConnected) {
                Log.d(TAG, "🚀 ULTRA-FAST offerer flow - INSTANT start for maximum speed")
                startOffererFlow()
            } else {
                Log.w(TAG, "⚠️ Cannot start offerer flow - destroyed: $isDestroyed, connected: ${AblySignalManager.isConnected}")
            }
        }
    }

    override fun onConnectionError(error: String) {
        Log.e(TAG, "❌ Signaling error: $error")
        connectionStateChangeListener(PeerConnection.IceConnectionState.FAILED)
    }

    // ===== CORE COMPONENTS =====
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var webRTCCore: WebRTCCore? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    // ===== MEDIA TRACKS =====
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var currentCameraId: String? = null

    // ===== STATE MANAGEMENT =====
    private var isConnected = false
    private var isConnecting = false
    private var mediaTracksAdded = false
    private var isCreatingOffer = false
    private var isTorchOn = false
    private var deviceId: String? = null
    private var lastConnectionAttempt: Long = 0

    // ===== CAMERA STATE PERSISTENCE =====
    // ✅ FIX: Add camera state persistence to prevent automatic switching
    private var lastSelectedCameraId: String? = null
    @Volatile
    private var cameraSwitchInProgress = false
    private val cameraSwitchLock = Object()

    // ===== RESOURCE MANAGEMENT =====
    private val activeHandlers = mutableSetOf<android.os.Handler>()
    private val activeRunnables = mutableSetOf<Runnable>()

    // ===== INITIALIZATION =====

    fun initialize(apiKey: String, deviceId: String) {
        Log.d(TAG, "🔗 Initializing WebRTC for device: $deviceId")
        this.deviceId = deviceId

        try {
            // Clean up any existing resources first
            cleanup()

            // ✅ FIX: Restore torch state before initializing
            restoreTorchState()

            // Initialize WebRTC core
            webRTCCore = WebRTCCore(context, signalCallback, ::onConnectionStateChanged)

            // Fetch ICE servers synchronously
            runBlocking {
                fetchIceServers()
            }

            Log.d(TAG, "✅ WebRTCManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize WebRTCManager: ${e.message}", e)
        }
    }

    // ===== CONNECTION MANAGEMENT =====

    private fun startOffererFlow() {
        Log.d(TAG, "🚀 Starting ULTRA-FAST offerer flow")

        // Remove cooldown for maximum speed - we want instant connection
        lastConnectionAttempt = System.currentTimeMillis()

        try {
            // CRITICAL FIX: Verify signaling is still connected before proceeding
            if (!AblySignalManager.isConnected) {
                Log.w(TAG, "⚠️ Cannot start offerer flow - signaling not connected")
                return
            }
            
            // Prepare audio immediately for fastest setup
            prepareAudioForCall()

            // Check permissions
            if (!hasRequiredPermissions()) {
                Log.w(TAG, "⚠️ Missing permissions - cannot proceed")
                return
            }

            // Initialize WebRTC if needed
            if (webRTCCore?.peerConnection == null) {
                webRTCCore?.init(iceServers.ifEmpty { getDefaultIceServers() })
            }

            // Setup media tracks immediately
            setupMediaTracks()
            
            Log.d(TAG, "✅ ULTRA-FAST offerer flow completed - OFFER should be sent to parent")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start ULTRA-FAST offerer flow: ${e.message}", e)
        }
    }

    private fun prepareAudioForCall() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            Log.d(TAG, "✅ Audio prepared for call")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Audio preparation failed: ${e.message}")
        }
    }

    private fun setupMediaTracks() {
        Log.d(TAG, "🎯 Setting up media tracks for: $currentStreamType")

        try {
            when (currentStreamType) {
                StreamType.NONE -> {
                    Log.d(TAG, "⚠️ StreamType.NONE - no media setup needed")
                }
                StreamType.AUDIO_ONLY -> setupAudioOnly()
                StreamType.AUDIO_VIDEO -> setupAudioVideo()
            }

            // Add tracks to peer connection immediately
            addMediaTracksToConnection()

            // CRITICAL FIX: Create offer IMMEDIATELY after media setup
            Log.d(TAG, "📵 Creating WebRTC offer IMMEDIATELY for ultra-fast connection...")
            webRTCCore?.createOffer()
            
            Log.d(TAG, "✅ OFFER creation initiated - this should trigger OFFER signal to parent")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup media tracks: ${e.message}", e)
        }
    }

    private fun setupAudioOnly() {
        Log.d(TAG, "🎤 Setting up audio-only tracks")
        cleanupAudioResources()
        localAudioTrack = createAudioTrack()
    }

    private fun setupAudioVideo() {
        Log.d(TAG, "📹 Setting up audio-video tracks")
        setupAudioOnly()
        setupVideo()
    }

    private fun setupVideo() {
        Log.d(TAG, "📷 Setting up video track")
        cleanupVideoResources()

        try {
            val (capturer, helper) = createVideoCapturer()
            videoCapturer = capturer
            surfaceTextureHelper = helper

            val videoSource = webRTCCore?.peerConnectionFactory?.createVideoSource(false)
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)

            // Try to start capture with fallback resolutions
            if (!tryStartVideoCapture(videoCapturer)) {
                Log.w(TAG, "⚠️ Video capture failed, switching to audio-only")
                switchToAudioOnly()
                return
            }

            localVideoTrack = webRTCCore?.peerConnectionFactory?.createVideoTrack("kidVideoTrack", videoSource)
            localVideoTrack?.setEnabled(true)

            Log.d(TAG, "✅ Video track created successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Video setup failed: ${e.message}", e)
            switchToAudioOnly()
        }
    }

    private fun createVideoCapturer(): Pair<VideoCapturer?, SurfaceTextureHelper?> {
        try {
            // ✅ FIX: Camera2Enumerator का इस्तेमाल करें जो Xiaomi डिवाइस पर ज्यादा stable है
            val enumerator = Camera2Enumerator(context)

            val deviceNames = enumerator.deviceNames
            if (deviceNames.isEmpty()) {
                Log.e(TAG, "❌ No cameras found")
                return Pair(null, null)
            }

            Log.d(TAG, "📷 Available cameras: ${deviceNames.joinToString()}")

            // ✅ FIX: Remember last selected camera and use it, don't always default to front
            val cameraId = if (currentCameraId != null && deviceNames.contains(currentCameraId)) {
                // Use the previously selected camera if it's still available
                Log.d(TAG, "📷 Using previously selected camera: $currentCameraId")
                currentCameraId
            } else {
                // First time setup - prefer front for user experience, fallback to back
                val defaultCameraId = deviceNames.find { enumerator.isFrontFacing(it) } ?: deviceNames.first()
                Log.d(TAG, "📷 First time setup - selected camera: $defaultCameraId (front-facing: ${enumerator.isFrontFacing(defaultCameraId)})")
                currentCameraId = defaultCameraId
                defaultCameraId
            }

            Log.d(TAG, "📷 Final selected camera: $cameraId (front-facing: ${enumerator.isFrontFacing(cameraId)})")

            val capturer = enumerator.createCapturer(cameraId, null)
            if (capturer == null) {
                Log.e(TAG, "❌ Failed to create video capturer for camera: $cameraId")
                return Pair(null, null)
            }

            val helper = SurfaceTextureHelper.create("VideoCapturerThread", webRTCCore?.eglBaseContext)
            if (helper == null) {
                Log.e(TAG, "❌ Failed to create SurfaceTextureHelper")
                capturer.dispose()
                return Pair(null, null)
            }

            Log.d(TAG, "✅ Video capturer created successfully")
            return Pair(capturer, helper)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating video capturer: ${e.message}", e)
            return Pair(null, null)
        }
    }

    private fun tryStartVideoCapture(capturer: VideoCapturer?): Boolean {
        if (capturer == null) return false

        // First verify camera hardware is ready
        if (!ensureCameraHardwareReady()) {
            Log.e(TAG, "❌ Camera hardware not ready")
            return false
        }

        // ✅ FIX: अतिरिक्त सुरक्षा - कैमरा शुरू करने से पहले यह सुनिश्चित करें कि सब कुछ तैयार है
        if (webRTCCore?.peerConnectionFactory == null) {
            Log.e(TAG, "❌ PeerConnectionFactory is null, cannot start video capture")
            return false
        }

        val resolutions = getVideoResolutions()
        for ((width, height, fps) in resolutions) {
            try {
                Log.d(TAG, "🎥 Attempting video capture: ${width}x${height}@${fps}fps")

                capturer.startCapture(width, height, fps)
                Log.d(TAG, "✅ Video capture started: ${width}x${height}@${fps}fps")
                return true
            } catch (e: Exception) {
                // ✅ FIX: Handle CameraAccessException specifically
                if (e is android.hardware.camera2.CameraAccessException) {
                    Log.e(TAG, "❌ Camera access denied by policy: ${e.message}")
                    Log.w(TAG, "⚠️ Camera disabled by system policy - switching to audio-only mode")
                    // Don't retry other resolutions if camera is disabled by policy
                    return false
                }
                Log.w(TAG, "⚠️ Failed resolution ${width}x${height}@${fps}fps: ${e.message}")
            }
        }

        Log.e(TAG, "❌ All video capture resolutions failed")
        return false
    }

    private fun ensureCameraHardwareReady(): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList

            if (cameraIds.isEmpty()) {
                Log.e(TAG, "❌ No camera devices available")
                return false
            }

            // Test camera access
            val testCameraId = cameraIds.first()
            val characteristics = cameraManager.getCameraCharacteristics(testCameraId)

            // Check if camera is available
            val isAvailable = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            if (isAvailable == null) {
                Log.e(TAG, "❌ Camera hardware level not available")
                return false
            }

            Log.d(TAG, "✅ Camera hardware ready - ${cameraIds.size} cameras available")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Camera hardware check failed: ${e.message}", e)
            return false
        }
    }

    private fun getVideoResolutions(): List<Triple<Int, Int, Int>> {
        return if (isXiaomiDevice()) {
            // Xiaomi-specific resolutions for better compatibility
            listOf(
                Triple(320, 240, 15),
                Triple(176, 144, 15),
                Triple(352, 288, 15),
                Triple(640, 480, 10),
                Triple(320, 240, 10)
            )
        } else {
            listOf(
                Triple(640, 480, 15),
                Triple(320, 240, 15),
                Triple(352, 288, 15),
                Triple(176, 144, 15)
            )
        }
    }

    private fun switchToAudioOnly() {
        Log.d(TAG, "🔄 Switching to audio-only mode")
        cleanupVideoResources()
        // Audio track remains active
    }

    private fun createAudioTrack(): AudioTrack? {
        return try {
            val audioConstraints = MediaConstraints()
            val audioSource = webRTCCore?.peerConnectionFactory?.createAudioSource(audioConstraints)
            val track = webRTCCore?.peerConnectionFactory?.createAudioTrack("kidAudioTrack", audioSource)
            track?.setEnabled(true)
            Log.d(TAG, "✅ Audio track created")
            track
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create audio track: ${e.message}", e)
            null
        }
    }

    private fun addMediaTracksToConnection() {
        Log.d(TAG, "🔗 Adding media tracks to peer connection")

        localAudioTrack?.let { track ->
            webRTCCore?.addTrack(track)
            Log.d(TAG, "✅ Audio track added to connection")
        }

        localVideoTrack?.let { track ->
            webRTCCore?.addTrack(track)
            Log.d(TAG, "✅ Video track added to connection")
        }

        mediaTracksAdded = true
    }

    private fun createOffer() {
        if (isCreatingOffer) {
            Log.d(TAG, "⚠️ Offer creation already in progress")
            return
        }
        
        if (webRTCCore?.peerConnection == null) {
            Log.e(TAG, "❌ Cannot create offer - no peer connection")
            return
        }

        isCreatingOffer = true
        Log.d(TAG, "📡 Creating WebRTC offer to send to parent...")

        try {
            webRTCCore?.createOffer()
            Log.d(TAG, "✅ Offer creation initiated - this should trigger OFFER signal to parent")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create offer: ${e.message}", e)
            isCreatingOffer = false
        }
    }

    // ===== SIGNALING HANDLERS =====

    private fun handleRemoteSignal(message: SignalMessage) {
        when (message.type) {
            "ANSWER" -> handleAnswer(message)
            "ICE_CANDIDATE" -> handleIceCandidate(message)
            else -> Log.w(TAG, "Unknown signal type: ${message.type}")
        }
    }

    private fun handleAnswer(message: SignalMessage) {
        Log.d(TAG, "📨 Received ANSWER")
        message.sdp?.let { sdp ->
            webRTCCore?.setRemoteAnswer(sdp)
        }
    }

    private fun handleIceCandidate(message: SignalMessage) {
        message.candidate?.let { candidate ->
            try {
                val iceCandidate = org.webrtc.IceCandidate(
                    candidate.sdpMid,
                    candidate.sdpMLineIndex,
                    candidate.sdp
                )
                webRTCCore?.addIceCandidate(iceCandidate)
                Log.d(TAG, "✅ ICE candidate added successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to convert ICE candidate: ${e.message}", e)
            }
        } ?: run {
            Log.w(TAG, "Received ICE candidate message but candidate is null")
        }
    }

    private fun safeSendSignal(signal: SignalMessage) {
        if (AblySignalManager.isConnected) {
            AblySignalManager.sendMessage(signal)
        } else {
            Log.w(TAG, "⚠️ Signaling not connected, queuing signal: ${signal.type}")
            // Queue signal for when connection is ready
            postDelayed({
                if (AblySignalManager.isConnected) {
                    AblySignalManager.sendMessage(signal)
                }
            }, 1000)
        }
    }

    // ===== CONNECTION STATE MANAGEMENT =====

    private fun onConnectionStateChanged(state: PeerConnection.IceConnectionState) {
        Log.d(TAG, "🔄 Connection state changed to: $state")

        val wasConnected = isConnected
        isConnected = state == PeerConnection.IceConnectionState.CONNECTED ||
                     state == PeerConnection.IceConnectionState.COMPLETED

        // Handle connection established
        if (isConnected && !wasConnected && !mediaTracksAdded) {
            Log.d(TAG, "🎯 Connection established - ensuring media tracks")
            postDelayed({ setupMediaTracks() }, 500)
        }

        // Handle disconnection
        if (!isConnected && wasConnected) {
            Log.w(TAG, "⚠️ Connection lost")
            mediaTracksAdded = false
            // Auto-reconnect on failure
            if (state == PeerConnection.IceConnectionState.FAILED) {
                postDelayed({ startOffererFlow() }, RECONNECT_DELAY)
            }
        }

        connectionStateChangeListener(state)
    }

    // ===== PERMISSIONS =====

    private fun hasRequiredPermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val required = when (currentStreamType) {
            StreamType.NONE -> false // No permissions needed for NONE
            StreamType.AUDIO_ONLY -> audioGranted
            StreamType.AUDIO_VIDEO -> cameraGranted && audioGranted
        }

        if (!required) {
            Log.w(TAG, "⚠️ Missing permissions - Camera: $cameraGranted, Audio: $audioGranted")
        }

        return required
    }

    fun requestPermissions(activity: Activity) {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), 1001)
        }
    }

    // ===== CAMERA CONTROLS =====

    fun toggleTorch(): Boolean {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "⚠️ No camera permission")
            sendCommandResult("toggleTorch", false, "no_permission")
            return false
        }

        // ✅ FIX: Always try to use back camera for torch since front cameras rarely have flash
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList

        // Find back camera (usually has flash capability)
        val backCameraId = cameraIds.find { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            lensFacing == CameraCharacteristics.LENS_FACING_BACK
        }

        if (backCameraId == null) {
            Log.w(TAG, "⚠️ No back camera found for torch")
            sendCommandResult("toggleTorch", false, "no_back_camera")
            return false
        }

        return try {
            val characteristics = cameraManager.getCameraCharacteristics(backCameraId)
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

            if (hasFlash) {
                // ✅ FIX: Simply toggle torch on/off - NO automatic turn-off
                // ✅ FIX: Add synchronization to prevent race conditions
                synchronized(this) {
                    isTorchOn = !isTorchOn
                    cameraManager.setTorchMode(backCameraId, isTorchOn)
                    Log.d(TAG, "🔦 Torch ${if (isTorchOn) "ON" else "OFF"} (stays on until manually turned off)")

                    // ✅ FIX: Store torch state persistently to prevent automatic turn-off
                    System.setProperty("banniguard.torch.state", isTorchOn.toString())
                    System.setProperty("banniguard.torch.camera", backCameraId)

                    sendCommandResult("toggleTorch", true, if (isTorchOn) "on" else "off")
                    return true
                }
            }

            Log.w(TAG, "⚠️ Back camera does not have flash capability")
            sendCommandResult("toggleTorch", false, "not_supported")
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Torch toggle failed: ${e.message}", e)
            sendCommandResult("toggleTorch", false, "error")
            false
        }
    }

    // ✅ FIX: Enhanced camera switch with proper synchronization and no automatic switching back
    fun switchCamera(onResult: ((Boolean) -> Unit)? = null): Boolean {
        // ✅ FIX: Prevent multiple simultaneous camera switches
        synchronized(cameraSwitchLock) {
            if (cameraSwitchInProgress) {
                Log.w(TAG, "⚠️ Camera switch already in progress, ignoring duplicate request")
                sendCommandResult("switchCamera", false, "already_in_progress")
                onResult?.invoke(false)
                return false
            }

            if (!hasRequiredPermissions()) {
                Log.w(TAG, "⚠️ No camera permission")
                sendCommandResult("switchCamera", false, "no_permission")
                onResult?.invoke(false)
                return false
            }

            val cameraCapturer = videoCapturer as? CameraVideoCapturer ?: run {
                Log.w(TAG, "⚠️ No camera capturer available")
                sendCommandResult("switchCamera", false, "no_capturer")
                onResult?.invoke(false)
                return false
            }

            // ✅ FIX: Determine which camera to switch to based on current camera
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList

            // Find front and back camera IDs
            val frontCameraId = cameraIds.find { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                lensFacing == CameraCharacteristics.LENS_FACING_FRONT
            }

            val backCameraId = cameraIds.find { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                lensFacing == CameraCharacteristics.LENS_FACING_BACK
            }

            // Determine target camera (opposite of current)
            val isCurrentlyFront = currentCameraId == frontCameraId
            val targetCameraId = if (isCurrentlyFront) backCameraId else frontCameraId
            val targetIsFront = targetCameraId == frontCameraId

            Log.d(TAG, "📷 Current camera: ${if (isCurrentlyFront) "front" else "back"} (${currentCameraId}), Target: ${if (targetIsFront) "front" else "back"} (${targetCameraId})")

            if (targetCameraId == null) {
                Log.w(TAG, "⚠️ Target camera not available")
                sendCommandResult("switchCamera", false, "target_unavailable")
                onResult?.invoke(false)
                return false
            }

            // ✅ FIX: Set flag to prevent concurrent switches
            cameraSwitchInProgress = true

            return try {
                Log.d(TAG, "📷 Starting camera switch operation to ${if (targetIsFront) "front" else "back"}")
                cameraCapturer.switchCamera(object : CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        synchronized(cameraSwitchLock) {
                            // Update current camera ID after successful switch
                            currentCameraId = if (isFrontCamera) frontCameraId else backCameraId
                            lastSelectedCameraId = currentCameraId // ✅ FIX: Remember the selected camera
                            cameraSwitchInProgress = false // ✅ FIX: Reset flag
                            Log.d(TAG, "✅ Camera switched to ${if (isFrontCamera) "front" else "back"} (ID: ${currentCameraId})")

                            // ✅ FIX: Send detailed confirmation with camera info
                            sendCommandResult("switchCamera", true, if (isFrontCamera) "front" else "back")
                            onResult?.invoke(true)
                        }
                    }

                    override fun onCameraSwitchError(error: String?) {
                        synchronized(cameraSwitchLock) {
                            Log.e(TAG, "❌ Camera switch error: $error")
                            cameraSwitchInProgress = false // ✅ FIX: Reset flag on error
                            // ✅ FIX: Send detailed failure confirmation
                            sendCommandResult("switchCamera", false, "switch_error")
                            onResult?.invoke(false)
                        }
                    }
                })
                true
            } catch (e: Exception) {
                synchronized(cameraSwitchLock) {
                    Log.e(TAG, "❌ Camera switch failed: ${e.message}", e)
                    cameraSwitchInProgress = false // ✅ FIX: Reset flag on exception
                    sendCommandResult("switchCamera", false, "exception")
                    onResult?.invoke(false)
                }
                false
            }
        }
    }

    // ✅ FIX: Send command result back to parent app
    private fun sendCommandResult(command: String, success: Boolean, details: String = "") {
        try {
            // Send result via Ably signaling
            val resultMessage = SignalMessage(
                type = "CONTROL_CONFIRMATION",
                command = command,
                status = if (success) "success" else "failed",
                details = details,
                timestamp = System.currentTimeMillis()
            )

            safeSendSignal(resultMessage)
            Log.d(TAG, "📤 Sent command result: $command -> ${if (success) "success" else "failed"} ${if (details.isNotEmpty()) "($details)" else ""}")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to send command result: ${e.message}")
        }
    }

    // ===== ICE SERVERS =====

    private suspend fun fetchIceServers() {
        Log.d(TAG, "🔄 Fetching ICE servers")

        // Add STUN servers (always available)
        val stunServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        )

        // Try to fetch TURN servers with retry mechanism
        val turnServers = fetchTurnServersWithRetry()

        iceServers = stunServers + turnServers
        Log.d(TAG, "✅ ICE servers configured: ${iceServers.size} total (${stunServers.size} STUN, ${turnServers.size} TURN)")
    }

    private suspend fun fetchTurnServersWithRetry(maxRetries: Int = 3): List<PeerConnection.IceServer> {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                Log.d(TAG, "🔄 TURN server fetch attempt $attempt/$maxRetries")

                val turnServers = withTimeout(8000) { // Increased timeout to 8 seconds
                    val response = RetrofitClient.twilioInstance.getIceServers(
                        "Basic ${Base64.encodeToString("AC92cf55a087bc392a185be0b4cf24dedd:e2eab2934fc4068311c9577c7ddc0ef8".toByteArray(), Base64.NO_WRAP)}",
                        "AC92cf55a087bc392a185be0b4cf24dedd"
                    )

                    if (response.isSuccessful) {
                        response.body()?.ice_servers?.map { server ->
                            val url = server.url ?: server.urls ?: ""
                            if (url.isNotEmpty()) {
                                Log.d(TAG, "🔧 Processing TURN server: $url")
                                PeerConnection.IceServer.builder(url)
                                    .setUsername(server.username ?: "")
                                    .setPassword(server.credential ?: "")
                                    .createIceServer()
                            } else null
                        }?.filterNotNull() ?: emptyList()
                    } else {
                        Log.w(TAG, "⚠️ TURN server fetch failed (attempt $attempt): HTTP ${response.code()}")
                        emptyList()
                    }
                }

                if (turnServers.isNotEmpty()) {
                    Log.d(TAG, "✅ TURN servers fetched successfully on attempt $attempt")
                    return turnServers
                }

            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "⚠️ TURN server fetch failed (attempt $attempt): ${e.message}")

                // Wait before retry (exponential backoff)
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(1000L * attempt) // 1s, 2s, 3s delays
                }
            }
        }

        // All attempts failed
        Log.e(TAG, "❌ All TURN server fetch attempts failed. Last error: ${lastException?.message}")
        Log.w(TAG, "⚠️ Proceeding with STUN servers only - may have connectivity issues")

        return emptyList()
    }

    private fun getDefaultIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    }

    // ===== UTILITY METHODS =====

    private fun isXiaomiDevice(): Boolean {
        return android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
               android.os.Build.BRAND.equals("Xiaomi", ignoreCase = true)
    }

    private fun postDelayed(runnable: Runnable, delay: Long = 0) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        activeHandlers.add(handler)
        activeRunnables.add(runnable)

        if (delay > 0) {
            handler.postDelayed(runnable, delay)
        } else {
            handler.post(runnable)
        }
    }

    // ===== CLEANUP =====

    private fun cleanup() {
        Log.d(TAG, "🧹 Cleaning up WebRTCManager")

        cleanupHandlers()
        cleanupMediaTracks()

        webRTCCore?.dispose()
        webRTCCore = null

        isConnected = false
        isConnecting = false
        mediaTracksAdded = false
        isCreatingOffer = false
    }

    private fun cleanupHandlers() {
        activeHandlers.forEach { handler ->
            try {
                handler.removeCallbacksAndMessages(null)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error cleaning up handler: ${e.message}")
            }
        }
        activeHandlers.clear()
        activeRunnables.clear()
    }

    private fun cleanupMediaTracks() {
        Log.d(TAG, "🧹 Cleaning up media tracks")

        try {
            localAudioTrack?.dispose()
            localAudioTrack = null

            localVideoTrack?.dispose()
            localVideoTrack = null

            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cleaning up media tracks: ${e.message}", e)
        }
    }

    private fun cleanupAudioResources() {
        try {
            localAudioTrack?.dispose()
            localAudioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Audio cleanup error: ${e.message}")
        }
    }

    private fun cleanupVideoResources() {
        try {
            localVideoTrack?.dispose()
            localVideoTrack = null

            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Video cleanup error: ${e.message}")
        }
    }

    // ===== PUBLIC API =====

    fun isConnected(): Boolean = isConnected
    fun getCurrentCameraId(): String? = currentCameraId
    fun isTorchOn(): Boolean = isTorchOn
    fun getStreamType(): StreamType = currentStreamType

    // ✅ FIX: Restore torch state to prevent automatic turn-off
    fun restoreTorchState() {
        try {
            val savedTorchState = System.getProperty("banniguard.torch.state", "false").toBoolean()
            val savedCameraId = System.getProperty("banniguard.torch.camera")

            if (savedTorchState && savedCameraId != null) {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraIds = cameraManager.cameraIdList

                // Check if the saved camera is still available
                if (cameraIds.contains(savedCameraId)) {
                    val characteristics = cameraManager.getCameraCharacteristics(savedCameraId)
                    val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

                    if (hasFlash) {
                        cameraManager.setTorchMode(savedCameraId, true)
                        isTorchOn = true
                        Log.d(TAG, "🔦 Torch state restored to ON for camera: $savedCameraId")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error restoring torch state: ${e.message}")
        }
    }

    // ===== METHODS FOR REALTIME SERVICE COMPATIBILITY =====

    fun setDeviceId(deviceId: String) {
        this.deviceId = deviceId
        Log.d(TAG, "📱 Device ID set: $deviceId")
    }

    fun safeSendSignalPublic(signal: SignalMessage) {
        safeSendSignal(signal)
    }

    fun isSignalingConnected(): Boolean {
        return AblySignalManager.isConnected
    }

    fun logStatus() {
        Log.d(TAG, "🎯 WebRTC STATUS:")
        Log.d(TAG, "   - Connected: $isConnected")
        Log.d(TAG, "   - Connecting: $isConnecting")
        Log.d(TAG, "   - Media tracks added: $mediaTracksAdded")
        Log.d(TAG, "   - Creating offer: $isCreatingOffer")
        Log.d(TAG, "   - Stream type: $currentStreamType")
        Log.d(TAG, "   - Audio track: ${localAudioTrack != null}")
        Log.d(TAG, "   - Video track: ${localVideoTrack != null}")
        Log.d(TAG, "   - Camera ID: $currentCameraId")
        Log.d(TAG, "   - Torch: $isTorchOn")
    }

    // ===== DESTROY METHOD =====

    // ✅ FIX: Force destroy method for complete cleanup
    private fun forceDestroy() {
        Log.d(TAG, "🧹 Force destroying all resources in WebRTCManager instance")
        isDestroyed = true

        try {
            // Cancel all pending operations first
            cleanupHandlers()

            // Force disconnect all connections
            synchronized(this) {
                // Clean up media resources
                cleanupMediaTracks()

                // Clean up WebRTC core
                webRTCCore?.let { core ->
                    try {
                        core.dispose()
                        Log.d(TAG, "✅ WebRTC core disposed")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Error disposing WebRTC core: ${e.message}")
                    }
                }
                webRTCCore = null

                // Disconnect signaling
                try {
                    AblySignalManager.disconnect()
                    Log.d(TAG, "✅ Signaling disconnected")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error disconnecting signaling: ${e.message}")
                }

                // Reset audio manager
                try {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Audio manager reset error: ${e.message}")
                }

                // Clear all state variables
                isConnected = false
                isConnecting = false
                mediaTracksAdded = false
                isCreatingOffer = false
                currentCameraId = null
                isTorchOn = false
                deviceId = null
                currentStreamType = StreamType.NONE

                Log.d(TAG, "✅ Force destroy completed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during force destroy: ${e.message}", e)
        }
    }

    // ===== SIGNALING =====
    private val signalCallback: (SignalMessage) -> Unit = { message ->
        safeSendSignal(message)
    }

    // ===== CONNECTION MANAGEMENT =====

    fun disconnect() {
        Log.d(TAG, "🚨🚨🚨 NUCLEAR INSTANT DISCONNECT - TOTAL SYSTEM SHUTDOWN 🚨🚨🚨")

        try {
            // Force disconnect WebRTC
            webRTCCore?.let { core ->
                try {
                    core.forceClosePeerConnection()
                    Log.d(TAG, "✅ WebRTC disconnected")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error disconnecting WebRTC: ${e.message}")
                }
            }

            // Force disconnect signaling
            try {
                AblySignalManager.disconnect()
                Log.d(TAG, "✅ Signaling disconnected")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error disconnecting signaling: ${e.message}")
            }

            // Reset states
            isConnected = false
            isConnecting = false
            mediaTracksAdded = false
            isCreatingOffer = false

            Log.d(TAG, "✅ Disconnect completed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during disconnect: ${e.message}", e)
        }
    }

    // ===== STREAM TYPE MANAGEMENT =====

    fun setStreamType(newStreamType: StreamType) {
        if (currentStreamType != newStreamType) {
            Log.d(TAG, "🔄 Changing stream type from $currentStreamType to $newStreamType")
            currentStreamType = newStreamType
        }
    }

    fun startNewConnection(apiKey: String, deviceId: String, streamType: StreamType) {
        Log.d(TAG, "🚀 Starting new connection with stream type: $streamType")

        // Update stream type
        setStreamType(streamType)

        // Set device ID
        this.deviceId = deviceId

        // Start signaling connection
        startSignalingConnection(apiKey, deviceId)
    }

    fun startSignalingConnection(apiKey: String, deviceId: String) {
        Log.d(TAG, "🔗 Starting signaling connection for device: $deviceId")

        try {
            // Ensure clean state before starting
            ensureSignalingReady()

            AblySignalManager.setSignalListener(this)
            val channelName = "$deviceId-v2"
            AblySignalManager.connect(apiKey, channelName)
            Log.d(TAG, "✅ Signaling connection started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start signaling: ${e.message}", e)
        }
    }

    private fun ensureSignalingReady(): Boolean {
        return try {
            // Disconnect any existing connections first
            AblySignalManager.disconnect()

            // Small delay to ensure cleanup
            Thread.sleep(200)

            Log.d(TAG, "✅ Signaling ready for new connection")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error ensuring signaling ready: ${e.message}", e)
            false
        }
    }
}
