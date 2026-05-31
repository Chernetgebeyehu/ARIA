package com.cherinet.aria.automation

import android.content.Context
import android.provider.ContactsContract

data class ContactInfo(val name: String, val phoneNumber: String)

/**
 * ContactsHelper: ARIA's phonebook reader.
 *
 * When you say "call mom," ARIA needs to look up "mom" in your
 * contacts to get her real phone number. This class does that lookup.
 *
 * It uses Android's ContentResolver — a system that lets apps
 * safely read shared data (like contacts) without directly
 * touching the database file.
 */
class ContactsHelper(private val context: Context) {

    fun findContact(query: String): ContactInfo? = searchContacts(query).firstOrNull()

    fun searchContacts(query: String): List<ContactInfo> {
        val results = mutableListOf<ContactInfo>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
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