package com.bannigaurd.bannikid.managers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.DatabaseReference
import java.util.concurrent.TimeUnit

data class CallLogEntry(val number: String?, val type: String?, val date: Long?, val duration: Long?, val name: String?) {
    constructor() : this(null, null, null, null, null)
}

class CallLogManager(private val context: Context) {

    fun sync(deviceId: String, dbRef: DatabaseReference) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.w("CallLogManager", "Read Call Log permission not granted.")
            return
        }

        val logs = mutableListOf<CallLogEntry>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME
        )
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        // --- NAYA FILTER LOGIC ---
        val fourDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(4)
        val selection = "${CallLog.Calls.DATE} > ?"
        val selectionArgs = arrayOf(fourDaysAgo.toString())
        // --- FILTER LOGIC KHATAM ---

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection, // Filter add kiya
                selectionArgs, // Filter value add ki
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberCol = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                    val typeCol = cursor.getColumnIndex(CallLog.Calls.TYPE)
                    val dateCol = cursor.getColumnIndex(CallLog.Calls.DATE)
                    val durationCol = cursor.getColumnIndex(CallLog.Calls.DURATION)
                    val nameCol = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    do {
                        val type = when (cursor.getInt(typeCol)) {
                            CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            else -> "Unknown"
                        }
                        logs.add(
                            CallLogEntry(
                                cursor.getString(numberCol),
                                type,
                                cursor.getLong(dateCol),
                                cursor.getLong(durationCol),
                                cursor.getString(nameCol) ?: "Unknown"
                            )
                        )
                    } while (cursor.moveToNext())
                }
            }
            dbRef.child("callLogs").child(deviceId).setValue(logs)
        } catch (e: Exception) {
            Log.e("CallLogManager", "Error syncing call logs", e)
        }
    }
}