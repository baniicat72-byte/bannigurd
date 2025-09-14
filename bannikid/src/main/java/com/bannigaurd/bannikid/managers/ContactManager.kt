    package com.bannigaurd.bannikid.managers

    import android.Manifest
    import android.content.Context
    import android.content.pm.PackageManager
    import android.provider.ContactsContract
    import android.util.Log
    import androidx.core.content.ContextCompat
    import com.google.firebase.database.DatabaseReference

    data class Contact(val name: String?, val number: String?) {
        constructor() : this(null, null)
    }

    class ContactManager(private val context: Context) {

        fun sync(deviceId: String, dbRef: DatabaseReference) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("ContactManager", "Read Contacts permission not granted.")
                return
            }

            val contacts = mutableListOf<Contact>()
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numberCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        do {
                            contacts.add(
                                Contact(
                                    cursor.getString(nameCol),
                                    cursor.getString(numberCol)
                                )
                            )
                        } while (cursor.moveToNext())
                    }
                }
                dbRef.child("contacts").child(deviceId).setValue(contacts)
            } catch (e: Exception) {
                Log.e("ContactManager", "Error syncing contacts", e)
            }
        }
    }