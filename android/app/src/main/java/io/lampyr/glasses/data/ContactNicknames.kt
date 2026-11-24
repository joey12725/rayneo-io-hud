package io.lampyr.glasses.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** Phone contacts' nicknames by display name ("Alex Doe" -> ["Al"]), for the Beeper spoken find. Empty without
 * READ_CONTACTS or on any provider error; only names are read, never numbers or other contact data. */
object ContactNicknames {
    fun load(context: Context): Map<String, List<String>> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        return runCatching {
            val out = linkedMapOf<String, MutableList<String>>()
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Nickname.NAME),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext() && out.size < 2000) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    val nick = cursor.getString(1)?.trim().orEmpty()
                    if (name.isNotEmpty() && nick.isNotEmpty() && !nick.equals(name, true)) out.getOrPut(name) { mutableListOf() } += nick
                }
            }
            out.mapValues { it.value.distinct() }
        }.getOrDefault(emptyMap())
    }
}
