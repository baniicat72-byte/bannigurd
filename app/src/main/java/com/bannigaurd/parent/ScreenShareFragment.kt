package com.bannigaurd.parent

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bannigaurd.parent.databinding.FragmentScreenShareBinding
import com.bannigaurd.parent.managers.CommandManager
import com.bannigaurd.parent.webrtc.AblySignalManager
import com.bannigaurd.parent.webrtc.WebRTCManager

class ScreenShareFragment : Fragment() {

    private var _binding: FragmentScreenShareBinding? = null
    private val binding get() = _binding!!
    private lateinit var deviceId: String

    private lateinit var webRTCManager: WebRTCManager
    private var isControlEnabled = true
    private var isAudioMuted = false
    private lateinit var commandManager: CommandManager

    companion object {
        private const val ARG_DEVICE_ID = "DEVICE_ID"
        fun newInstance(deviceId: String) = ScreenShareFragment().apply {
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
        _binding = FragmentScreenShareBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (deviceId.isEmpty()) {
            Toast.makeText(requireContext(), "Device ID not found.", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }
        setupWebRTC()
        setupClickListeners()
        commandManager.sendCommand("startScreenShare", true)
    }

    private fun setupWebRTC() {
        // ✅ FIX: isVideoCall पैरामीटर को true पास किया गया है
        webRTCManager = WebRTCManager(requireContext(), AblySignalManager, isVideoCall = true)

        webRTCManager.setRemoteVideoSink(binding.surfaceViewRenderer)
        binding.surfaceViewRenderer.init(webRTCManager.eglContext, null)
        val ablyApiKey = "EKsSvA.Qq187A:u2jx5GyZQwIjAZNPg6XVWj1XwMP0LH-citEhl_aGiNo"
        webRTCManager.startConnection(ablyApiKey, deviceId)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupClickListeners() {
        binding.btnEndSession.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnToggleControl.setOnClickListener {
            isControlEnabled = !isControlEnabled
            if (isControlEnabled) {
                binding.btnToggleControl.setImageResource(R.drawable.ic_control_on)
                binding.btnToggleControl.setColorFilter(Color.GREEN)
            } else {
                binding.btnToggleControl.setImageResource(R.drawable.ic_control_off)
                binding.btnToggleControl.colorFilter = null
            }
        }

        binding.btnToggleAudio.setOnClickListener {
            isAudioMuted = !isAudioMuted
            webRTCManager.toggleAudio(isAudioMuted)
            if (isAudioMuted) {
                binding.btnToggleAudio.setImageResource(R.drawable.ic_mic_off)
            } else {
                binding.btnToggleAudio.setImageResource(R.drawable.ic_mic_on)
            }
        }

        binding.surfaceViewRenderer.setOnTouchListener { v, event ->
            if (!isControlEnabled) return@setOnTouchListener false

            val command = mapOf(
                "type" to "touch",
                "action" to event.action,
                "x" to event.x,
                "y" to event.y,
                "width" to v.width,
                "height" to v.height
            )
            webRTCManager.sendControlCommand(command)
            true
        }

        binding.btnHome.setOnClickListener { sendKeyCommand("home") }
        binding.btnRecents.setOnClickListener { sendKeyCommand("recent") }
        binding.btnBack.setOnClickListener { sendKeyCommand("back") }
    }

    private fun sendKeyCommand(action: String) {
        if (!isControlEnabled) return
        val command = mapOf("type" to "key", "action" to action)
        webRTCManager.sendControlCommand(command)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        commandManager.sendCommand("stopLiveStream", true)
        if(::webRTCManager.isInitialized) {
            webRTCManager.disconnect()
        }
        _binding = null
    }
}