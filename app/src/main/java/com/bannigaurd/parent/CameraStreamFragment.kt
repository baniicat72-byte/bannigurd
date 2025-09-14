// parent/CameraStreamFragment.kt

package com.bannigaurd.parent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bannigaurd.parent.databinding.FragmentCameraStreamBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bannigaurd.parent.managers.CommandManager
import com.bannigaurd.parent.webrtc.AblySignalManager
import com.bannigaurd.parent.webrtc.WebRTCConnectionListener
import com.bannigaurd.parent.webrtc.WebRTCManager
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

class CameraStreamFragment : Fragment(), WebRTCConnectionListener {

    private var _binding: FragmentCameraStreamBinding? = null
    private val binding get() = _binding!!
    private lateinit var deviceId: String

    private var webRTCManager: WebRTCManager? = null
    private lateinit var commandManager: CommandManager
    private var isTorchOn = false
    private var isAudioMuted = false
    private var remoteAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    private var isOfflineMode = false
    private var networkCheckHandler: Handler? = null
    private val networkCheckRunnable = object : Runnable {
        override fun run() {
            checkNetworkAndUpdateMode()
            networkCheckHandler?.postDelayed(this, 5000) // Check every 5 seconds
        }
    }

    // FIX: Prevent duplicate command sending
    private var lastCommandSent: String? = null
    private var lastCommandTime: Long = 0
    private val COMMAND_SEND_COOLDOWN = 3000L // 3 seconds cooldown between same commands

    // ✅ FIX: Add Job variable to manage delayed frame verification task
    private var frameVerificationJob: kotlinx.coroutines.Job? = null

    // Permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("CameraStreamFragment", "RECORD_AUDIO permission granted")
            // Permission granted, proceed with WebRTC setup
            setupWebRTC()
        } else {
            Log.w("CameraStreamFragment", "RECORD_AUDIO permission denied")
            Toast.makeText(requireContext(), "Audio permission is required for camera streaming", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
        }
    }


    companion object {
        private const val ARG_DEVICE_ID = "DEVICE_ID"
        fun newInstance(deviceId: String) = CameraStreamFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_DEVICE_ID, deviceId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = arguments?.getString(ARG_DEVICE_ID) ?: ""
        commandManager = CommandManager(deviceId)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraStreamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (deviceId.isEmpty()) {
            Toast.makeText(requireContext(), "Device ID not found.", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }

        // Start network monitoring
        startNetworkMonitoring()

        // Check RECORD_AUDIO permission before proceeding
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d("CameraStreamFragment", "RECORD_AUDIO permission not granted, requesting...")
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Log.d("CameraStreamFragment", "RECORD_AUDIO permission already granted")
            // Send command to kid FIRST, then setup WebRTC
            sendCommandWithCooldown("startLiveCamera")
            setupWebRTC()
        }
        setupClickListeners()
    }

    private fun setupWebRTC() {
        // FIX: Check if we can start a new connection
        val currentManager = webRTCManager
        if (currentManager != null && !currentManager.canStartNewConnection()) {
            Log.w("CameraStreamFragment", "⚠️ Cannot start new connection - already connected or cleanup needed")
            return
        }

        // FIX: Clean up existing WebRTC manager if it exists and cannot start new connection
        if (currentManager != null && !currentManager.canStartNewConnection()) {
            try {
                Log.d("CameraStreamFragment", "🧹 Cleaning up existing WebRTC manager")
                currentManager.disconnect()
                webRTCManager = null
                Log.d("CameraStreamFragment", "✅ Existing WebRTC manager cleaned up")
            } catch (e: Exception) {
                Log.w("CameraStreamFragment", "⚠️ Error cleaning up existing WebRTC manager: ${e.message}")
            }
        }

        // Create new WebRTC manager
        webRTCManager = WebRTCManager(requireContext(), AblySignalManager, isVideoCall = true)
        webRTCManager?.connectionListener = this

        // FIX: Ensure SurfaceViewRenderer is properly initialized with correct EGL context
        try {
            // Clear any existing renderer state
            binding.surfaceViewRenderer.release()

            // Initialize with proper EGL context and error handling
            val currentManager = webRTCManager
            if (currentManager != null) {
                val eglContext = currentManager.eglContext
                if (eglContext != null) {
                    binding.surfaceViewRenderer.init(eglContext, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {
                            Log.d("CameraStreamFragment", "First frame rendered successfully!")
                        }

                        override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                            Log.d("CameraStreamFragment", "Frame resolution changed: ${videoWidth}x${videoHeight}, rotation: $rotation")
                            // Adjust renderer size based on video resolution
                            activity?.runOnUiThread {
                                binding.surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                            }
                        }
                    })

                    // Configure renderer properties
                    binding.surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    binding.surfaceViewRenderer.setEnableHardwareScaler(true)
                    binding.surfaceViewRenderer.setMirror(false)
                    binding.surfaceViewRenderer.setZOrderMediaOverlay(false)

                    Log.d("CameraStreamFragment", "SurfaceViewRenderer initialized successfully")
                } else {
                    Log.e("CameraStreamFragment", "EGL context is null, cannot initialize SurfaceViewRenderer")
                    // Try to recover by creating a new WebRTC manager
                    webRTCManager = WebRTCManager(requireContext(), AblySignalManager, isVideoCall = true)
                    webRTCManager?.connectionListener = this
                    val newEglContext = webRTCManager?.eglContext
                    if (newEglContext != null) {
                        binding.surfaceViewRenderer.init(newEglContext, null)
                        Log.d("CameraStreamFragment", "✅ Recovery successful with new EGL context")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CameraStreamFragment", "Failed to initialize SurfaceViewRenderer: ${e.message}", e)
            // Try to recover with simpler initialization
            try {
                val manager = webRTCManager
                if (manager != null) {
                    binding.surfaceViewRenderer.init(manager.eglContext, null)
                    binding.surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    Log.d("CameraStreamFragment", "✅ Fallback initialization successful")
                }
            } catch (e2: Exception) {
                Log.e("CameraStreamFragment", "❌ Fallback initialization also failed: ${e2.message}")
            }
        }

        // FIX: Check if we can start a new connection
        webRTCManager?.let { manager ->
            if (manager.canStartNewConnection()) {
                val ablyApiKey = "EKsSvA.Qq187A:u2jx5GyZQwIjAZNPg6XVWj1XwMP0LH-citEhl_aGiNo"
                Log.d("CameraStreamFragment", "🚀 Starting new WebRTC connection")
                manager.startConnection(ablyApiKey, deviceId)
                
                // ✅ FIX: Add connection timeout and status updates
                binding.tvStatus.text = "Waiting for child response..."
                startConnectionTimeoutTimer()
            } else {
                Log.w("CameraStreamFragment", "⚠️ Cannot start new connection - already connected or cleanup needed")
            }
        }
    }
    
    // ✅ FIX: Add connection timeout timer
    private fun startConnectionTimeoutTimer() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isAdded || _binding == null) return@postDelayed
            
            val currentStatus = binding.tvStatus.text.toString()
            if (currentStatus == "Waiting for child response..." || currentStatus == "Connecting...") {
                Log.w("CameraStreamFragment", "⚠️ Connection timeout - child device not responding")
                binding.tvStatus.text = "Child device not responding"
                binding.tvStatus.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
                Toast.makeText(requireContext(), "Child device is not responding. Please ensure the child app is running and has network access.", Toast.LENGTH_LONG).show()
                // Don't auto-disconnect, let user decide
            }
        }, 20000) // 20 second timeout
    }

    private fun setupClickListeners() {
        // FIX: Prevent accidental disconnects - only end button should close
        binding.btnEndCamera.setOnClickListener {
            Log.d("CameraStreamFragment", "End camera button clicked - disconnecting")
            disconnectAndClose()
        }

        binding.btnSwitchCamera.setOnClickListener {
            Log.d("CameraStreamFragment", "Switch camera button clicked")
            // Use Firebase for camera controls (child device listens via Firebase)
            commandManager.sendCommand("switchCamera", true)
            // Show feedback to user
            Toast.makeText(requireContext(), "Switching camera...", Toast.LENGTH_SHORT).show()
        }

        binding.btnToggleTorch.setOnClickListener {
            Log.d("CameraStreamFragment", "Toggle torch button clicked")
            isTorchOn = !isTorchOn
            // Use Firebase for torch controls (child device listens via Firebase)
            commandManager.sendCommand("toggleTorch", true)

            // Update button icon immediately for better UX
            if (isTorchOn) {
                binding.btnToggleTorch.setImageResource(R.drawable.ic_flashlight_on)
                Toast.makeText(requireContext(), "Torch ON", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnToggleTorch.setImageResource(R.drawable.ic_flashlight_off)
                Toast.makeText(requireContext(), "Torch OFF", Toast.LENGTH_SHORT).show()
            }
        }

        // FIX: Prevent accidental touches on video area from triggering disconnect
        binding.surfaceViewRenderer.setOnClickListener {
            // Do nothing - prevent accidental disconnects
            Log.d("CameraStreamFragment", "Video area touched - ignoring to prevent accidental disconnect")
        }

        // FIX: Add touch listener to root view to handle accidental touches
        binding.root.setOnClickListener {
            // Do nothing - prevent accidental disconnects
            Log.d("CameraStreamFragment", "Root view touched - ignoring to prevent accidental disconnect")
        }
    }

    fun disconnectAndClose() {
        Log.d("CameraStreamFragment", "🚨 INSTANT DISCONNECT INITIATED")

        // ✅ FIX: Cancel frame verification job immediately
        frameVerificationJob?.cancel()
        frameVerificationJob = null

        // ✅ FIX: Stop network monitoring immediately
        stopNetworkMonitoring()

        // ✅ FIX: Send stop command FIRST (before WebRTC cleanup)
        try {
            commandManager.sendCommand("stopLiveStream", true)
            Log.d("CameraStreamFragment", "📤 Stop command sent immediately")
        } catch (e: Exception) {
            Log.w("CameraStreamFragment", "⚠️ Error sending stop command: ${e.message}")
        }

        // ✅ FIX: Force immediate WebRTC disconnect (no background thread delay)
        try {
            val currentManager = webRTCManager
            if (currentManager != null) {
                currentManager.forceCompleteCleanup()
                Log.d("CameraStreamFragment", "✅ WebRTC manager force cleaned up")
            }
        } catch (e: Exception) {
            Log.w("CameraStreamFragment", "⚠️ Error during WebRTC cleanup: ${e.message}")
        }

        // ✅ FIX: Immediate UI cleanup (no delay)
        performImmediateUICleanup()

        // ✅ FIX: Close fragment immediately (no delay)
        if (isAdded) {
            Log.d("CameraStreamFragment", "🔙 Closing fragment immediately")
            parentFragmentManager.popBackStack()
        }
    }

    // ✅ FIX: New method for immediate UI cleanup
    fun performImmediateUICleanup() {
        try {
            // Clear video renderer immediately
            if (_binding != null) {
                binding.surfaceViewRenderer.clearImage()
                binding.surfaceViewRenderer.release()
            }

            // Reset UI state immediately
            binding.tvStatus.text = "Disconnected"
            binding.tvStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))

            // Disable all buttons immediately
            binding.btnSwitchCamera.isEnabled = false
            binding.btnToggleTorch.isEnabled = false
            binding.btnEndCamera.isEnabled = false

            Log.d("CameraStreamFragment", "✅ UI cleaned up immediately")

        } catch (e: Exception) {
            Log.w("CameraStreamFragment", "⚠️ Error during UI cleanup: ${e.message}")
        }
    }

    // WebRTCConnectionListener के मेथड
    override fun onConnectionStateChanged(state: PeerConnection.IceConnectionState) {
        activity?.runOnUiThread {
            // Check if fragment is still attached before updating UI
            if (!isAdded || context == null) return@runOnUiThread

            Log.d("CameraStreamFragment", "🔄 Connection state changed to: $state")

            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    Log.d("CameraStreamFragment", "✅ WebRTC connection established")
                    Toast.makeText(requireContext(), "Connected!", Toast.LENGTH_SHORT).show()
                    binding.tvStatus.text = "Streaming Live..."
                    binding.tvStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                    // Reset reconnect attempts on successful connection
                    reconnectAttempts = 0
                    // Enable control buttons
                    binding.btnSwitchCamera.isEnabled = true
                    binding.btnToggleTorch.isEnabled = true

                    // After a short delay, dump inbound video stats to verify frames are arriving
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val manager = webRTCManager
                            manager?.dumpInboundVideoStats()
                        } catch (e: Exception) {
                            Log.w("CameraStreamFragment", "Failed to dump inbound video stats: ${e.message}")
                        }
                    }, 3000)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Log.w("CameraStreamFragment", "⚠️ WebRTC connection disconnected")
                    Toast.makeText(requireContext(), "Connection Lost. Retrying...", Toast.LENGTH_SHORT).show()
                    binding.tvStatus.text = "Reconnecting..."
                    binding.tvStatus.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
                    // Disable control buttons during reconnection
                    binding.btnSwitchCamera.isEnabled = false
                    binding.btnToggleTorch.isEnabled = false

                    // ✅ FIX: Safely clean up video resources to prevent native crashes
                    safeCleanupVideoResources()

                    // Try to reconnect after a delay
                    reconnectWithDelay()
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Log.e("CameraStreamFragment", "❌ WebRTC connection failed")
                    Toast.makeText(requireContext(), "Connection Failed. Retrying...", Toast.LENGTH_SHORT).show()
                    binding.tvStatus.text = "Reconnecting..."
                    binding.tvStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    // Disable control buttons during reconnection
                    binding.btnSwitchCamera.isEnabled = false
                    binding.btnToggleTorch.isEnabled = false

                    // ✅ FIX: Safely clean up video resources to prevent native crashes
                    safeCleanupVideoResources()

                    // Try to reconnect after a delay
                    reconnectWithDelay()
                }
                PeerConnection.IceConnectionState.CLOSED -> {
                    Log.d("CameraStreamFragment", "🔒 WebRTC connection closed")
                    Toast.makeText(requireContext(), "Connection Closed", Toast.LENGTH_SHORT).show()
                    binding.tvStatus.text = "Connection Closed"
                    binding.tvStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    // Close fragment after connection is closed
                    parentFragmentManager.popBackStack()
                }
                PeerConnection.IceConnectionState.CHECKING -> {
                    Log.d("CameraStreamFragment", "🔍 WebRTC connection checking")
                    binding.tvStatus.text = "Connecting..."
                    binding.tvStatus.setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
                    // Disable control buttons during connection
                    binding.btnSwitchCamera.isEnabled = false
                    binding.btnToggleTorch.isEnabled = false
                }
                else -> {
                    Log.d("CameraStreamFragment", "ℹ️ WebRTC connection state: $state")
                    binding.tvStatus.text = "Connecting..."
                    binding.tvStatus.setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
                }
            }
        }
    }

    override fun onRemoteAudioTrack(track: AudioTrack) {
        Log.d("CameraStreamFragment", "✅ Received remote audio track from kid. Track ID: ${track.id()}, Enabled: ${track.enabled()}")

        // Ensure audio is enabled for camera streams
        track.setEnabled(true)
        remoteAudioTrack = track

        // FIX: WebRTC audio tracks are automatically played through the system's audio output
        // when they're enabled and added to the peer connection. No need for custom sinks.
        Log.d("CameraStreamFragment", "🎵 Audio track setup verification:")
        Log.d("CameraStreamFragment", "   - Track enabled: ${track.enabled()}")
        Log.d("CameraStreamFragment", "   - Track state: ${track.state()}")
        Log.d("CameraStreamFragment", "   - Track ID: ${track.id()}")

        // Verify audio playback setup
        verifyAudioPlayback(track)
    }

    private fun verifyAudioPlayback(track: AudioTrack) {
        activity?.runOnUiThread {
            if (!isAdded || context == null) return@runOnUiThread

            Log.d("CameraStreamFragment", "🎵 Audio track verification:")
            Log.d("CameraStreamFragment", "   - Track enabled: ${track.enabled()}")
            Log.d("CameraStreamFragment", "   - Track state: ${track.state()}")
            Log.d("CameraStreamFragment", "   - AudioManager mode: ${context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager}")

            // Check if audio is actually playing
            val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                Log.d("CameraStreamFragment", "   - Speakerphone: ${audioManager.isSpeakerphoneOn}")
                Log.d("CameraStreamFragment", "   - Music volume: ${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}")
                Log.d("CameraStreamFragment", "   - Voice call volume: ${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}")
            }
        }
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        activity?.runOnUiThread {
            if (!isAdded || context == null || _binding == null) return@runOnUiThread

            Log.d("CameraStreamFragment", "✅ Parent received remote video track! Rendering...")
            Log.d("CameraStreamFragment", "Video track state: enabled=${track.enabled()}, state=${track.state()}")
            remoteVideoTrack = track

            try {
                // FIX: Clear any existing sink first with error handling
                try {
                    remoteVideoTrack?.removeSink(binding.surfaceViewRenderer)
                } catch (e: Exception) {
                    Log.w("CameraStreamFragment", "⚠️ Warning removing existing sink: ${e.message}")
                }

                // FIX: Ensure SurfaceViewRenderer is properly configured
                binding.surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                binding.surfaceViewRenderer.setEnableHardwareScaler(true)
                binding.surfaceViewRenderer.setMirror(false)
                binding.surfaceViewRenderer.setZOrderMediaOverlay(false)

                // FIX: Add video track to renderer with error handling
                remoteVideoTrack?.addSink(binding.surfaceViewRenderer)

                // FIX: Force layout update
                binding.surfaceViewRenderer.visibility = View.VISIBLE
                binding.surfaceViewRenderer.requestLayout()

                // FIX: Ensure video track is enabled
                remoteVideoTrack?.setEnabled(true)
                Log.d("CameraStreamFragment", "Video track enabled: ${remoteVideoTrack?.enabled()}")

                // Update UI to show connected state
                binding.tvStatus.text = "Streaming Live..."
                binding.tvStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                binding.btnSwitchCamera.isEnabled = true
                binding.btnToggleTorch.isEnabled = true

                // FIX: Add frame verification with better logging
                addFrameVerification(track)

                Log.d("CameraStreamFragment", "Video track added to renderer successfully")
            } catch (e: Exception) {
                Log.e("CameraStreamFragment", "❌ Error setting up video track: ${e.message}", e)
                Toast.makeText(requireContext(), "Error displaying video", Toast.LENGTH_SHORT).show()
                
                // Try recovery after a short delay
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isAdded && _binding != null) {
                        try {
                            remoteVideoTrack?.addSink(binding.surfaceViewRenderer)
                            Log.d("CameraStreamFragment", "✅ Recovery attempt for video renderer")
                        } catch (e2: Exception) {
                            Log.e("CameraStreamFragment", "❌ Recovery failed: ${e2.message}")
                        }
                    }
                }, 1000)
            }
        }
    }

    private fun addFrameVerification(track: VideoTrack) {
        Log.d("CameraStreamFragment", "🎥 Adding frame verification listener")

        // ✅ FIX: Cancel any existing frame verification job
        frameVerificationJob?.cancel()

        // ✅ FIX: Create new job and store it
        frameVerificationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Add a custom sink to count frames
                val frameCounter = object : org.webrtc.VideoSink {
                    private var frameCount = 0
                    private var lastFrameTime = System.currentTimeMillis()

                    override fun onFrame(frame: org.webrtc.VideoFrame) {
                        frameCount++
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastFrameTime

                        if (frameCount % 30 == 0) { // Log every 30 frames
                            Log.d("CameraStreamFragment", "🎥 Frame received! Count: $frameCount, Size: ${frame.buffer.width}x${frame.buffer.height}, Time since last: ${timeDiff}ms")
                        }

                        lastFrameTime = currentTime

                        // Verify frame is valid
                        if (frame.buffer.width <= 0 || frame.buffer.height <= 0) {
                            Log.w("CameraStreamFragment", "⚠️ Invalid frame dimensions: ${frame.buffer.width}x${frame.buffer.height}")
                        }
                    }
                }

                // Add frame counter as additional sink
                track.addSink(frameCounter)

                // ✅ FIX: Wait for 5 seconds with proper coroutine delay
                kotlinx.coroutines.delay(5000)

                // ✅ FIX: Check if job is still active and track is still valid before accessing
                if (frameVerificationJob?.isActive == true && remoteVideoTrack != null && remoteVideoTrack == track) {
                    Log.d("CameraStreamFragment", "🎥 Frame verification check after 5 seconds")
                    if (track.enabled()) {
                        Log.d("CameraStreamFragment", "🎥 Video track is enabled and should be receiving frames")
                    } else {
                        Log.w("CameraStreamFragment", "⚠️ Video track is disabled")
                    }
                } else {
                    Log.d("CameraStreamFragment", "ℹ️ Frame verification job cancelled or track changed")
                }

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d("CameraStreamFragment", "🎥 Frame verification job cancelled")
                } else {
                    Log.e("CameraStreamFragment", "❌ Error in frame verification: ${e.message}", e)
                }
            }
        }
    }

    private fun reconnectWithDelay() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.w("CameraStreamFragment", "Max reconnect attempts reached. Giving up.")
            activity?.runOnUiThread {
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "Unable to reconnect. Please try again.", Toast.LENGTH_LONG).show()
                    binding.tvStatus.text = "Connection Failed"
                    // Close fragment after max attempts
                    parentFragmentManager.popBackStack()
                }
            }
            return
        }

        reconnectAttempts++
        // Exponential backoff: 1s, 2s, 4s
        val delay = 1000L * (1L shl (reconnectAttempts - 1))

        Log.d("CameraStreamFragment", "Reconnect attempt $reconnectAttempts/$maxReconnectAttempts in ${delay}ms")

        Handler(Looper.getMainLooper()).postDelayed({
            // Check if fragment is still alive before reconnecting
            if (!isAdded || context == null || _binding == null) return@postDelayed

            Log.d("CameraStreamFragment", "Attempting to reconnect WebRTC...")
            // Disconnect current connection if exists
            try {
                val currentManager = webRTCManager
                if (currentManager != null) {
                    currentManager.disconnect()
                }
            } catch (e: Exception) {
                Log.w("CameraStreamFragment", "WebRTCManager not initialized or already disconnected during reconnect")
            }
            // Restart connection
            setupWebRTC()
            sendCommandWithCooldown("startLiveCamera")
        }, delay)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d("CameraStreamFragment", "🚨 onDestroyView called - INSTANT CLEANUP")

        // ✅ FIX: Cancel all background jobs immediately
        frameVerificationJob?.cancel()
        frameVerificationJob = null

        // ✅ FIX: Stop network monitoring immediately
        stopNetworkMonitoring()

        // ✅ FIX: Send stop command FIRST and IMMEDIATELY
        try {
            commandManager.sendCommand("stopLiveStream", true)
            Log.d("CameraStreamFragment", "📤 Stop command sent immediately in onDestroyView")
        } catch (e: Exception) {
            Log.w("CameraStreamFragment", "⚠️ Error sending stop command in onDestroyView: ${e.message}")
        }

        // ✅ FIX: Force complete cleanup of WebRTC manager
        try {
            val currentManager = webRTCManager
            if (currentManager != null) {
                currentManager.forceCompleteCleanup()
                Log.d("CameraStreamFragment", "✅ WebRTC manager force cleaned up in onDestroyView")
            }
        } catch (e: Exception) {
            Log.w("CameraStreamFragment", "⚠️ Error during WebRTC cleanup in onDestroyView: ${e.message}")
        }

        // ✅ FIX: Release renderer immediately
        try {
            if (_binding != null) {
                binding.surfaceViewRenderer.release()
                Log.d("CameraStreamFragment", "✅ SurfaceViewRenderer released immediately")
            }
        } catch (e: Exception) {
            Log.w("CameraStreamFragment", "⚠️ Error releasing SurfaceViewRenderer: ${e.message}")
        }

        // ✅ FIX: Clear binding immediately
        _binding = null

        Log.d("CameraStreamFragment", "✅ onDestroyView cleanup completed instantly")
    }

    // Network Detection Methods
    private fun startNetworkMonitoring() {
        networkCheckHandler = Handler(Looper.getMainLooper())
        networkCheckHandler?.post(networkCheckRunnable)
        Log.d("CameraStreamFragment", "Started network monitoring")
    }

    private fun stopNetworkMonitoring() {
        networkCheckHandler?.removeCallbacks(networkCheckRunnable)
        networkCheckHandler = null
        Log.d("CameraStreamFragment", "Stopped network monitoring")
    }

    private fun checkNetworkAndUpdateMode() {
        val hasNetwork = isNetworkAvailable()
        val wasOffline = isOfflineMode
        isOfflineMode = !hasNetwork

        if (wasOffline != isOfflineMode) {
            activity?.runOnUiThread {
                if (!isAdded || context == null || _binding == null) return@runOnUiThread

                if (isOfflineMode) {
                    enterOfflineMode()
                } else {
                    exitOfflineMode()
                }
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return connectivityManager?.let { cm ->
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } ?: false
    }

    private fun enterOfflineMode() {
        Log.w("CameraStreamFragment", "🔴 Entering OFFLINE MODE - No internet connection")
        binding.tvStatus.text = "Offline Mode - Limited Features"
        binding.tvStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))

        // Show offline message
        Toast.makeText(requireContext(),
            "No internet connection. Camera controls may not work in real-time.",
            Toast.LENGTH_LONG).show()

        // Disable real-time features but keep UI functional
        // Camera controls will still send commands but may not reach device
    }

    private fun exitOfflineMode() {
        Log.i("CameraStreamFragment", "🟢 Exiting OFFLINE MODE - Internet restored")
        binding.tvStatus.text = "Reconnecting..."
        binding.tvStatus.setTextColor(resources.getColor(android.R.color.white, null))

        Toast.makeText(requireContext(),
            "Internet connection restored. Reconnecting...",
            Toast.LENGTH_SHORT).show()

        // Reset connection attempts and try to reconnect
        reconnectAttempts = 0
        reconnectWithDelay()
    }

    override fun onResume() {
        super.onResume()
        // Resume network monitoring when fragment becomes visible
        if (networkCheckHandler == null) {
            startNetworkMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause network monitoring when fragment is not visible
        stopNetworkMonitoring()
    }

    // FIX: Add method to prevent duplicate command sending
    private fun sendCommandWithCooldown(command: String) {
        val currentTime = System.currentTimeMillis()

        // Check if same command was sent recently
        if (lastCommandSent == command && (currentTime - lastCommandTime) < COMMAND_SEND_COOLDOWN) {
            Log.d("CameraStreamFragment", "⚠️ Skipping duplicate command: $command (cooldown active)")
            return
        }

        // Update tracking variables
        lastCommandSent = command
        lastCommandTime = currentTime

        // Send the command
        Log.d("CameraStreamFragment", "📤 Sending command with cooldown: $command")
        commandManager.sendCommand(command, true)
    }

    // ✅ FIX: Safe video resource cleanup to prevent native crashes
    private fun safeCleanupVideoResources() {
        try {
            Log.d("CameraStreamFragment", "🧹 Safely cleaning up video resources")

            // Safely remove video track from renderer
            if (remoteVideoTrack != null && _binding != null) {
                try {
                    remoteVideoTrack?.removeSink(binding.surfaceViewRenderer)
                    Log.d("CameraStreamFragment", "✅ Video track removed from renderer")
                } catch (e: Exception) {
                    Log.w("CameraStreamFragment", "⚠️ Error removing video track from renderer: ${e.message}")
                }
            }

            // Safely dispose of video track
            try {
                remoteVideoTrack?.dispose()
                remoteVideoTrack = null
                Log.d("CameraStreamFragment", "✅ Video track disposed")
            } catch (e: Exception) {
                Log.w("CameraStreamFragment", "⚠️ Error disposing video track: ${e.message}")
            }

            // Safely dispose of audio track
            try {
                remoteAudioTrack?.dispose()
                remoteAudioTrack = null
                Log.d("CameraStreamFragment", "✅ Audio track disposed")
            } catch (e: Exception) {
                Log.w("CameraStreamFragment", "⚠️ Error disposing audio track: ${e.message}")
            }

            // Clear renderer with null checks
            if (_binding != null) {
                try {
                    binding.surfaceViewRenderer.clearImage()
                    Log.d("CameraStreamFragment", "✅ SurfaceViewRenderer cleared")
                } catch (e: Exception) {
                    Log.w("CameraStreamFragment", "⚠️ Error clearing SurfaceViewRenderer: ${e.message}")
                }
            }

            Log.d("CameraStreamFragment", "✅ Video resources cleanup completed safely")

        } catch (e: Exception) {
            Log.e("CameraStreamFragment", "❌ Error during video resource cleanup: ${e.message}", e)
        }
    }
}
