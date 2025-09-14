package com.bannigaurd.parent

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bannigaurd.parent.databinding.FragmentUtilitiesBinding
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class UtilitiesFragment : Fragment() {

    private var _binding: FragmentUtilitiesBinding? = null
    private val binding get() = _binding!!

    private var childDeviceId: String? = null
    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference
    private lateinit var commandRef: DatabaseReference

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUtilitiesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fetchLinkedDeviceAndSetupListeners()
    }

    private fun fetchLinkedDeviceAndSetupListeners() {
        val parentUid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(parentUid).get().addOnSuccessListener { doc ->
            if (!isAdded) return@addOnSuccessListener
            val devices = doc.get("linkedDevices") as? List<String>
            childDeviceId = devices?.firstOrNull()

            if (childDeviceId == null) {
                Toast.makeText(context, "No linked device found.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            commandRef = rtdb.child("commands").child(childDeviceId!!)
            setupClickListeners()
        }
    }

    // --- FIX: कमांड भेजने के लिए एक नया फंक्शन बनाया गया है ---
    private fun sendCommand(commandName: String, value: Any) {
        val command = mapOf(
            "value" to value,
            "timestamp" to ServerValue.TIMESTAMP
        )
        commandRef.child(commandName).setValue(command)
    }


    private fun setupClickListeners() {
        // --- FIX: अब सभी क्लिक लिसनर नए sendCommand फंक्शन का उपयोग करेंगे ---
        binding.cardLockDevice.setOnClickListener {
            sendCommand("lockDevice", true)
            Toast.makeText(context, "Sending lock command...", Toast.LENGTH_SHORT).show()
        }

        binding.cardPlayAlert.setOnClickListener {
            sendCommand("playAlarm", true)
            Toast.makeText(context, "Sending alert command...", Toast.LENGTH_SHORT).show()
        }

        binding.btnSendMessage.setOnClickListener {
            val message = binding.etSpeakMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                sendCommand("speakText", message)
                Toast.makeText(context, "Sending message...", Toast.LENGTH_SHORT).show()
                binding.etSpeakMessage.text?.clear()
            } else {
                Toast.makeText(context, "Please enter a message.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.cardDirectChat.setOnClickListener {
            val intent = Intent(activity, DeviceChatActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}