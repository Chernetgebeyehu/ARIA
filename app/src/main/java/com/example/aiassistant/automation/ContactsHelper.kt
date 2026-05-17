package com.example.aiassistant.automation

import android.content.Context
import android.provider.ContactsContract

data class ContactInfo(
    val name: String,
    val phoneNumber: String
)

class ContactsHelper(private val context: Context) {

    /**
     * Find a contact by partial name match.
     * Returns the first match, or null.
     */
    fun findContact(query: String): ContactInfo? {
        val contacts = searchContacts(query)
        return contacts.firstOrNull()
    }

    fun searchContacts(query: String): List<ContactInfo> {
        val results = mutableListOf<ContactInfo>()

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} " +
                        "LIKE ?",
                arrayOf("%$query%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    results.add(ContactInfo(name, number))
                }
            }
        } catch (e: SecurityException) {
            // Contacts permission not granted
        }

        return results
    }
}