package com.bannigaurd.bannikid.managers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.bannigaurd.bannikid.AppConstants
import com.bannigaurd.bannikid.RealtimeService
import com.google.firebase.database.*

class CommandManager(private val context: Context) {

    private var commandRef: DatabaseReference? = null
    private var commandListener: ValueEventListener? = null
    private val TAG = "CommandManager_Kid"

    // Enhanced thread safety with better synchronization
    @Volatile
    private var isProcessingCommand = false
    private val commandLock = Object()
    private val queueLock = Object() // Separate lock for queue operations

    // Improved command queuing system
    private var pendingCommand: String? = null
    private var pendingDeviceId: String? = null
    private var lastCommandTime: Long = 0
    private val COMMAND_COOLDOWN = 2000L // 2 seconds between commands

    // Enhanced command queue management
    private data class CommandData(val command: String, val deviceId: String, val timestamp: Long = System.currentTimeMillis())
    private val commandQueue = mutableListOf<CommandData>()
    private val MAX_QUEUE_SIZE = 10 // Limit queue size to prevent memory issues

    fun listenForCommands(deviceId: String, database: DatabaseReference) {
        if (commandListener != null) {
            commandRef?.removeEventListener(commandListener!!)
        }
        commandRef = database.child("commands").child(deviceId)

        commandListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Read data from Firebase properly
                val commandData = snapshot.getValue(object : GenericTypeIndicator<Map<String, Any>>() {})
                val command = commandData?.get("command") as? String ?: return

                // CRITICAL FIX: ULTRA-AGGRESSIVE deduplication to prevent flooding
                synchronized(commandLock) {
                    // EMERGENCY STOP has absolute priority
                    if (command == "stopLiveStream") {
                        // Clear all state and process stop immediately
                        isProcessingCommand = false // Force clear processing state
                        pendingCommand = null
                        pendingDeviceId = null
                        commandQueue.clear()
                        Log.d(TAG, "🚨 EMERGENCY STOP - clearing all state for instant response")
                        
                        // Process stop immediately on current thread
                        processCommand(command, deviceId)
                        
                        // Clear Firebase immediately and return
                        commandRef?.removeValue()
                        return
                    }
                    
                    // For other commands, check if already processing same type
                    if (isProcessingCommand) {
                        val currentType = when (pendingCommand) {
                            "startLiveAudio", "startLiveCamera" -> "start"
                            "toggleTorch" -> "torch"
                            "switchCamera" -> "camera"
                            else -> pendingCommand
                        }
                        
                        val newType = when (command) {
                            "startLiveAudio", "startLiveCamera" -> "start"
                            "toggleTorch" -> "torch"
                            "switchCamera" -> "camera"
                            else -> command
                        }
                        
                        if (currentType == newType) {
                            Log.w(TAG, "🚫 IGNORING duplicate command type '$command' - already processing '$pendingCommand'")
                            commandRef?.removeValue() // Clear this duplicate immediately
                            return
                        }
                        
                        // Different command type - queue it
                        Log.w(TAG, "⚠️ Queuing different command: '$command' (processing: $pendingCommand)")
                        pendingCommand = command
                        pendingDeviceId = deviceId
                        return
                    }
                    
                    // Start processing new command
                    isProcessingCommand = true
                    Log.d(TAG, "✅ ULTRA-FAST Processing: $command for device: $deviceId")
                }
                
                // Process command with ultra-fast response
                processCommand(command, deviceId)

                // CRITICAL FIX: Immediate reset for maximum speed
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    synchronized(commandLock) {
                        isProcessingCommand = false
                        Log.d(TAG, "✅ ULTRA-FAST processing finished. Ready immediately.")
                    }
                    commandRef?.removeValue()?.addOnSuccessListener {
                        Log.d(TAG, "🗑️ Command cleared instantly")
                    }
                    // Process pending command immediately
                    processPendingCommand()
                }, 200) // Ultra-fast 200ms instead of 1000ms
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Command listener cancelled", error.toException())
                synchronized(commandLock) {
                    isProcessingCommand = false
                }
                // Process pending command even on cancellation
                processPendingCommand()
            }
        }
        commandRef?.addValueEventListener(commandListener!!)
    }

    private fun processCommand(command: String, deviceId: String) {
        val ablyApiKey = "EKsSvA.Qq187A:u2jx5GyZQwIjAZNPg6XVWj1XwMP0LH-citEhl_aGiNo"

        // CRITICAL FIX: Aggressive command deduplication to prevent flooding
        synchronized(queueLock) {
            when (command) {
                "stopLiveStream" -> {
                    // Remove ALL pending commands when stop is received - instant response
                    commandQueue.clear()
                    pendingCommand = null
                    pendingDeviceId = null
                    Log.d(TAG, "🧹 CLEARED all pending commands for immediate stop")
                }
                "startLiveAudio", "startLiveCamera" -> {
                    // Remove any conflicting commands
                    commandQueue.removeAll { it.command == "stopLiveStream" || it.command.startsWith("start") }
                    if (pendingCommand == "stopLiveStream" || pendingCommand?.startsWith("start") == true) {
                        pendingCommand = null
                        pendingDeviceId = null
                    }
                    Log.d(TAG, "🧹 Cleared conflicting commands for $command")
                }
                "switchCamera", "toggleTorch" -> {
                    // Remove duplicate control commands
                    commandQueue.removeAll { it.command == command }
                    Log.d(TAG, "🧹 Removed duplicate $command commands")
                }
            }
        }

        when (command) {
            "startLiveAudio", "startLiveCamera" -> {
                Log.d(TAG, "🎤/📷 ULTRA-FAST start command: $command")
                
                // CRITICAL FIX: Immediate execution without delay for ultra-fast response
                val serviceIntent = Intent(context, RealtimeService::class.java).apply {
                    action = if (command == "startLiveAudio") AppConstants.START_AUDIO_ACTION else AppConstants.START_CAMERA_ACTION
                    putExtra("DEVICE_ID", deviceId)
                    putExtra("ABLY_API_KEY", ablyApiKey)
                    putExtra("COMMAND_TIMESTAMP", System.currentTimeMillis())
                    putExtra("ULTRA_FAST_START", true) // Flag for immediate processing
                }
                
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.d(TAG, "✅ ULTRA-FAST RealtimeService started for $command")
                    
                    // Send immediate status update to parent
                    sendCommandConfirmation(command, "processing")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start RealtimeService: ${e.message}", e)
                    sendCommandConfirmation(command, "failed")
                }
            }
            "stopLiveStream" -> {
                Log.d(TAG, "🛑 INSTANT stop command - immediate broadcast")
                // CRITICAL FIX: Immediate stop without delay
                val stopIntent = Intent(AppConstants.STOP_ACTION)
                stopIntent.putExtra("IMMEDIATE_STOP", true)
                context.sendBroadcast(stopIntent)
                
                // Also stop any foreground service immediately
                try {
                    val serviceIntent = Intent(context, RealtimeService::class.java)
                    context.stopService(serviceIntent)
                    Log.d(TAG, "✅ Service stopped immediately")
                } catch (e: Exception) {
                    Log.w(TAG, "Service stop attempted: ${e.message}")
                }
            }
            "switchCamera" -> {
                Log.d(TAG, "📷 INSTANT camera switch")
                sendCommandConfirmation(command, "processing")
                context.sendBroadcast(Intent(AppConstants.SWITCH_CAMERA_ACTION))
            }
            "toggleTorch" -> {
                Log.d(TAG, "🔦 INSTANT torch toggle")
                sendCommandConfirmation(command, "processing")
                context.sendBroadcast(Intent(AppConstants.TOGGLE_TORCH_ACTION))
            }
        }
    }

    private fun startServiceWithCheck(intent: Intent) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "✅ Service started successfully for action: ${intent.action}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start service for action ${intent.action}: ${e.message}", e)
        }
    }

    // Process pending command method
    private fun processPendingCommand() {
        synchronized(commandLock) {
            if (pendingCommand != null && pendingDeviceId != null) {
                val command = pendingCommand!!
                val deviceId = pendingDeviceId!!

                // Clear pending command
                pendingCommand = null
                pendingDeviceId = null

                // Start processing new command
                isProcessingCommand = true
                Log.d(TAG, "🔄 FAST processing queued: $command for device: $deviceId")
                processCommand(command, deviceId)

                // CRITICAL FIX: Faster timer for queued commands too
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    synchronized(commandLock) {
                        isProcessingCommand = false
                        Log.d(TAG, "✅ FAST queued processing finished. Ready for next.")
                    }
                    commandRef?.removeValue()?.addOnSuccessListener {
                        Log.d(TAG, "🗑️ Queued command cleared")
                    }
                    // Check again for more pending commands
                    processPendingCommand()
                }, 200) // Ultra-fast 200ms timer for immediate next command
            }
        }
    }

    // Send immediate confirmation for fast UI feedback
    private fun sendCommandConfirmation(command: String, status: String) {
        try {
            // Send confirmation back to parent app via Firebase
            val confirmationData = mapOf(
                "command" to command,
                "status" to status,
                "timestamp" to System.currentTimeMillis()
            )

            commandRef?.child("confirmation")?.setValue(confirmationData)?.addOnSuccessListener {
                Log.d(TAG, "✅ Command confirmation sent: $command -> $status")
            }?.addOnFailureListener { e ->
                Log.w(TAG, "⚠️ Failed to send command confirmation: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error sending command confirmation: ${e.message}")
        }
    }

    fun stopListening() {
        commandListener?.let { listener ->
            commandRef?.removeEventListener(listener)
        }
        commandListener = null
        commandRef = null
        Log.d(TAG, "Command listener stopped")
    }
}
