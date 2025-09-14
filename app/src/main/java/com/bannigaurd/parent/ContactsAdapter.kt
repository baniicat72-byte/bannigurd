    package com.bannigaurd.parent

    import android.content.Context
    import android.view.LayoutInflater
    import android.view.View
    import android.view.ViewGroup
    import android.widget.TextView
    import androidx.recyclerview.widget.RecyclerView

    class ContactsAdapter(
        private val context: Context,
        private val contacts: List<Contact>
    ) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_contact_card, parent, false)
            return ContactViewHolder(view)
        }

        override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
            holder.bind(contacts[position])
        }

        override fun getItemCount(): Int = contacts.size

        class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameTextView: TextView = itemView.findViewById(R.id.tvContactName)
            private val numberTextView: TextView = itemView.findViewById(R.id.tvContactNumber)

            fun bind(contact: Contact) {
                nameTextView.text = contact.name ?: "No Name"
                numberTextView.text = contact.number ?: "No Number"
            }
        }
    }