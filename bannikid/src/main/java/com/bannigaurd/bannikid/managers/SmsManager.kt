        package com.bannigaurd.bannikid.managers

        import android.Manifest
        import android.content.Context
        import android.content.pm.PackageManager
        import android.provider.Telephony
        import android.util.Log
        import androidx.core.content.ContextCompat
        import com.google.firebase.database.DatabaseReference
        import java.util.concurrent.TimeUnit

        data class SmsMessage(val address: String?, val body: String?, val date: Long?, val type: String?) {
            constructor() : this(null, null, null, null)
        }

        class SmsManager(private val context: Context) {

            fun sync(deviceId: String, dbRef: DatabaseReference) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                    Log.w("SmsManager", "Read SMS permission not granted.")
                    return
                }

                val allMessages = mutableListOf<SmsMessage>()

                // --- NAYA FILTER LOGIC ---
                val fourDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(4)
                val selection = "${Telephony.Sms.DATE} > ?"
                val selectionArgs = arrayOf(fourDaysAgo.toString())
                // --- FILTER LOGIC KHATAM ---

                try {
                    context.contentResolver.query(
                        Telephony.Sms.CONTENT_URI,
                        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                        selection, // Filter add kiya
                        selectionArgs, // Filter value add ki
                        "${Telephony.Sms.DATE} DESC"
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val addressCol = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                            val bodyCol = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                            val dateCol = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                            val typeCol = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                            do {
                                val type = if (cursor.getInt(typeCol) == Telephony.Sms.MESSAGE_TYPE_INBOX) "inbox" else "sent"
                                allMessages.add(
                                    SmsMessage(
                                        cursor.getString(addressCol),
                                        cursor.getString(bodyCol),
                                        cursor.getLong(dateCol),
                                        type
                                    )
                                )
                            } while (cursor.moveToNext())
                        }
                    }
                    dbRef.child("sms").child(deviceId).setValue(allMessages)
                } catch (e: Exception) {
                    Log.e("SmsManager", "Error syncing SMS", e)
                }
            }
        }