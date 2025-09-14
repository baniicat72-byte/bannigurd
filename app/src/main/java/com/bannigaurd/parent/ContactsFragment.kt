package com.bannigaurd.parent

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class ContactsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private var childDeviceId: String? = null
    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference

    @SuppressLint("MissingInflatedId")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_contacts, container, false)
        recyclerView = view.findViewById(R.id.rvContacts)
        recyclerView.layoutManager = GridLayoutManager(context, 2)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fetchLinkedDevice()
    }

    private fun fetchLinkedDevice() {
        val parentUid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(parentUid).get().addOnSuccessListener { doc ->
            val devices = doc.get("linkedDevices") as? List<String>
            if (!devices.isNullOrEmpty()) {
                childDeviceId = devices[0]
                fetchContacts()
            }
        }
    }

    private fun fetchContacts() {
        if (childDeviceId == null) return
        val contactsRef = rtdb.child("contacts").child(childDeviceId!!)
        contactsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                val contacts = mutableListOf<Contact>()
                snapshot.children.forEach {
                    it.getValue(Contact::class.java)?.let { contact -> contacts.add(contact) }
                }
                if (view != null) {
                    recyclerView.adapter = ContactsAdapter(requireContext(), contacts)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                if(isAdded) Toast.makeText(context, "Failed to load contacts.", Toast.LENGTH_SHORT).show()
            }
        })
    }
}