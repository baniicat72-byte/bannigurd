// parent/LiveAudioFragment.kt

package com.bannigaurd.parent

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
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
import com.bannigaurd.parent.databinding.FragmentLiveAudioBinding
import com.bannigaurd.parent.managers.AudioStreamRecorder
import com.bannigaurd.parent.managers.CommandManager
import com.bannigaurd.parent.webrtc.AblySignalManager
import com.bannigaurd.parent.webrtc.WebRTCConnectionListener
import com.bannigaurd.parent.webrtc.WebRTCManager
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LiveAudioFragment : Fragment(), WebRTCConnectionListener {

    private var _binding: FragmentLiveAudioBinding? = null
    private val binding get() = _binding!!
    private lateinit var deviceId: String

    private lateinit var webRTCManager: WebRTCManager
    private lateinit var commandManager: CommandManager
    private val TAG = "LiveAudioFragment"

    private var audioStreamRecorder: AudioStreamRecorder? = null
    private var isRecording = false
    private var isConnectionEstablished = false

    // ✅ FIX: डुप्लीकेट डिस्कनेक्ट कॉल्स को रोकने के लिए यह फ्लैग जोड़ें
    private var isDisconnecting = false

    // ऑडियो डेटा के लिए बफ़र
    private var audioDataBuffer: ByteArray? = null

    companion object {
        private const val ARG_DEVICE_ID = "DEVICE_ID"
        fun newInstance(deviceId: String) = LiveAudioFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_DEVICE_ID, deviceId)
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "RECORD_AUDIO permission granted.")
                // Permission granted, proceed with WebRTC setup
                setupWebRTC()
            } else {
                Toast.makeText(requireContext(), "Recording permission is required for this feature.", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = arguments?.getString(ARG_DEVICE_ID) ?: ""
        if (deviceId.isEmpty()) {
            Toast.makeText(requireContext(), "Device ID not found.", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }
        commandManager = CommandManager(deviceId)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLiveAudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().volumeControlStream = AudioManager.STREAM_VOICE_CALL

        // Check RECORD_AUDIO permission before proceeding
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "RECORD_AUDIO permission not granted, requesting...")
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Log.d(TAG, "RECORD_AUDIO permission already granted")
            // Send command to kid FIRST, then setup WebRTC
            commandManager.sendCommand("startLiveAudio", true)
            setupWebRTC()
        }
        setupClickListeners()
        binding.tvStatus.text = "Connecting..."
        binding.waveformView.startAnimation()
    }

    private fun setupWebRTC() {
        webRTCManager = WebRTCManager(requireContext(), AblySignalManager, isVideoCall = false)
        webRTCManager.connectionListener = this

        val ablyApiKey = "EKsSvA.Qq187A:u2jx5GyZQwIjAZNPg6XVWj1XwMP0LH-citEhl_aGiNo"
        webRTCManager.startConnection(ablyApiKey, deviceId)
        
        // ✅ FIX: Add connection timeout
        binding.tvStatus.text = "Waiting for child response..."
        startConnectionTimeoutTimer()
    }
    
    // ✅ FIX: Add connection timeout timer
    private fun startConnectionTimeoutTimer() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isConnectionEstablished && !isDisconnecting) {
                Log.w(TAG, "⚠️ Connection timeout - child device not responding")
                binding.tvStatus.text = "Child device not responding"
                Toast.makeText(requireContext(), "Child device is not responding. Please ensure the child app is running.", Toast.LENGTH_LONG).show()
                // Don't auto-disconnect, let user decide
            }
        }, 20000) // 20 second timeout
    }

    private fun setupClickListeners() {
        binding.btnEndAudio.setOnClickListener {
            // ✅ FIX: बटन को तुरंत डिसेबल करें और स्टेटस बदलें
            it.isEnabled = false
            binding.tvStatus.text = "Disconnecting..."
            disconnectAndClose()
        }

        binding.btnRecordAudio.setOnClickListener {
             // ... (यह कोड वैसा ही रहेगा) ...
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            audioStreamRecorder?.stop()
            audioStreamRecorder = null
            isRecording = false
            updateRecordButtonUI()
            Toast.makeText(context, "Recording saved to Downloads/BanniGuard", Toast.LENGTH_LONG).show()
        } else {
            val appDir = FileManager.getAppDirectory()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "LiveAudio_${timestamp}_Recording.wav"
            val recordingFile = File(appDir, fileName)

            audioStreamRecorder = AudioStreamRecorder()
            audioStreamRecorder?.start(recordingFile)
            isRecording = true
            updateRecordButtonUI()
            Toast.makeText(context, "Recording started...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRecordButtonUI() {
        if (isRecording) {
            binding.btnRecordAudio.setColorFilter(ContextCompat.getColor(requireContext(), R.color.status_red))
        } else {
            binding.btnRecordAudio.colorFilter = null
        }
    }

    override fun onConnectionStateChanged(state: PeerConnection.IceConnectionState) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread

            // ✅ FIX: कनेक्शन की स्थिति के आधार पर बटन को इनेबल/डिसेबल करें
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    isConnectionEstablished = true
                    binding.tvStatus.text = "Connected! Listening..."
                    binding.btnEndAudio.isEnabled = true
                    binding.btnRecordAudio.isEnabled = true
                }
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.CLOSED -> {
                    isConnectionEstablished = false
                    binding.tvStatus.text = "Connection Lost"
                    binding.waveformView.stopAnimation()
                    binding.btnEndAudio.isEnabled = true // Re-enable to allow closing
                    binding.btnRecordAudio.isEnabled = false
                    if (isRecording) {
                        toggleRecording()
                    }
                }
                else -> {
                    isConnectionEstablished = false
                    binding.tvStatus.text = "Connecting..."
                    binding.btnEndAudio.isEnabled = true // Allow user to cancel
                    binding.btnRecordAudio.isEnabled = false
                    binding.waveformView.startAnimation()
                }
            }
        }
    }

    override fun onRemoteAudioTrack(track: AudioTrack) {
        activity?.runOnUiThread {
            Log.d(TAG, "✅ Parent received remote audio track! Playing...")
            track.setEnabled(true)
            
            // Mark connection as established to prevent timeout message
            isConnectionEstablished = true

            // FIX: WebRTC audio tracks are automatically played through the system's audio output
            // when they're enabled and added to the peer connection. No need for custom sinks.
            Log.d(TAG, "🎵 Audio track setup verification:")
            Log.d(TAG, "   - Track enabled: ${track.enabled()}")
            Log.d(TAG, "   - Track state: ${track.state()}")
            Log.d(TAG, "   - Track ID: ${track.id()}")

            // Ensure audio manager is properly configured for WebRTC audio
            val audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            
            // Force audio mode to COMMUNICATION for better audio quality
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                Log.d(TAG, "🔄 Forcing AudioManager mode to MODE_IN_COMMUNICATION")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            
            // Ensure volume is audible
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (currentVolume < maxVolume / 3) {
                Log.d(TAG, "🔊 Increasing voice call volume to ensure audibility")
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume / 2, 0)
            }
            
            Log.d(TAG, "   - AudioManager mode: ${audioManager.mode}")
            Log.d(TAG, "   - Speakerphone: ${audioManager.isSpeakerphoneOn}")
            Log.d(TAG, "   - Voice call volume: ${audioManager.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)}")

            // Update UI to show audio is playing
            if (_binding != null) {
                binding.tvStatus.text = "Connected! Audio Playing..."
                binding.btnRecordAudio.isEnabled = true
                Log.d(TAG, "✅ Audio should now be playing through speakers")
            }
        }
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        // लाइव ऑडियो के लिए वीडियो ट्रैक को अनदेखा करें
        Log.w(TAG, "Received unexpected video track in LiveAudioFragment. Ignoring.")
    }

    // ✅ FIX: इस मेथड को सरल बनाएं
    fun disconnectAndClose() {
        if (isDisconnecting) return
        isDisconnecting = true

        binding.btnEndAudio.isEnabled = false
        binding.tvStatus.text = "Disconnecting..."

        // Kid को बताएं कि स्ट्रीम बंद करनी है
        commandManager.sendCommand("stopLiveStream", true)

        // WebRTC को स्थानीय रूप से बंद करें
        webRTCManager.disconnect()

        // तुरंत fragment बंद करें
        if (isAdded && !isStateSaved) {
            parentFragmentManager.popBackStack()
        }
    }

    // ✅ FIX: Add immediate cleanup method for DashboardActivity
    fun performImmediateCleanup() {
        Log.d(TAG, "🚨 Performing immediate cleanup of LiveAudioFragment")

        try {
            // Stop recording if active
            if (isRecording) {
                audioStreamRecorder?.stop()
                audioStreamRecorder = null
                isRecording = false
            }

            // Stop waveform animation
            if (_binding != null) {
                binding.waveformView.stopAnimation()
            }

            // Send stop command immediately
            try {
                commandManager.sendCommand("stopLiveStream", true)
                Log.d(TAG, "📤 Stop command sent immediately")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error sending stop command: ${e.message}")
            }

            // Force WebRTC cleanup
            try {
                webRTCManager.forceCompleteCleanup()
                Log.d(TAG, "✅ WebRTC force cleaned up immediately")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during WebRTC cleanup: ${e.message}")
            }

            // Clear binding
            _binding = null

            Log.d(TAG, "✅ LiveAudioFragment immediate cleanup completed")

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error during LiveAudioFragment immediate cleanup: ${e.message}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView called, cleaning up.")

        // स्ट्रीमिंग बंद करें
        disconnectAndClose()

        // रिकॉर्डिंग रोकें अगर चल रही है
        if (isRecording) {
            audioStreamRecorder?.stop()
            audioStreamRecorder = null
        }

        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called, ensuring disconnect.")

        // ऐप बंद होने पर भी स्ट्रीमिंग बंद करें
        disconnectAndClose()
    }
}
