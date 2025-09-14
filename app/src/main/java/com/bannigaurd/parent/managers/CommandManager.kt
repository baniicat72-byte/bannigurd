package com.bannigaurd.parent.managers

import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class CommandManager(private val deviceId: String) {

    // ✅ FIX: Path "devices/.../command" से बदलकर "commands/..." कर दिया गया है
    private val commandRef = FirebaseDatabase.getInstance().reference.child("commands").child(deviceId)
    private val TAG = "CommandManager"

    fun sendCommand(command: String, clearAfter: Boolean = false) {
        val commandData = mapOf(
            "command" to command,
            "timestamp" to System.currentTimeMillis()
        )
        commandRef.setValue(commandData)
            .addOnSuccessListener {
                Log.d(TAG, "Command '$command' sent successfully.")
                if (clearAfter) {
                    clearCommandAfterDelay()
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to send command '$command'", it)
            }
    }

    private fun clearCommandAfterDelay() {
        android.os.Handler().postDelayed({
            commandRef.removeValue()
                .addOnSuccessListener {
                    Log.d(TAG, "Command cleared from Firebase.")
                }
        }, 2000)
    }
}