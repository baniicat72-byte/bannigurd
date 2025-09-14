package com.bannigaurd.parent.webrtc

import org.webrtc.IceCandidate

// यह डेटा क्लास WebRTC सिग्नलिंग मैसेज के लिए है
data class SignalMessage(
    val type: String, // "OFFER", "ANSWER", "ICE_CANDIDATE", "CAMERA_SWITCH", "TORCH_TOGGLE", "CONTROL_CONFIRMATION"
    val sdp: String? = null, // SessionDescription का डेटा
    val candidate: IceCandidateModel? = null, // ICE Candidate का डेटा
    val command: String? = null, // Control command data
    val status: String? = null, // Status for control confirmations ("success", "failed")
    val details: String? = null, // Additional details for control confirmations (e.g., "front", "back", "on", "off", "not_supported")
    val timestamp: Long? = null, // Timestamp for control commands
    val sender: String? = null // Sender identifier to prevent echo (child/parent)
)

// IceCandidate को JSON में बदलने के लिए एक सहायक क्लास
data class IceCandidateModel(
    val sdp: String,
    val sdpMLineIndex: Int,
    val sdpMid: String
)

// WebRTC के IceCandidate को हमारे मॉडल में बदलने के लिए एक्सटेंशन फंक्शन
fun IceCandidate.toModel(): IceCandidateModel {
    return IceCandidateModel(this.sdp, this.sdpMLineIndex, this.sdpMid)
}

// हमारे मॉडल को वापस WebRTC के IceCandidate में बदलने के लिए एक्सटेंशन फंक्शन
fun IceCandidateModel.toIceCandidate(): IceCandidate {
    return IceCandidate(this.sdpMid, this.sdpMLineIndex, this.sdp)
}
