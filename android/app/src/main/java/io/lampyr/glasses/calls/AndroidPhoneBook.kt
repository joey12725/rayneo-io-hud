package io.lampyr.glasses.calls

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import io.lampyr.glasses.data.ActionResult
import kotlinx.coroutines.*

/**
 * ContactsContract + CallLog for the Phone app. Everything stays on the phone; queries run on IO, each guarded by
 * its permission, and an empty book plus [access] flags is returned instead of throwing. Cached in memory and
 * reloaded when Android reports a change (ContentObserver) or the Phone app opens.
 */
class AndroidPhoneBook(private val context: Context, private val scope: CoroutineScope) : PhoneBook {
    private val listeners = linkedSetOf<() -> Unit>()
    override var access = PhoneBookAccess(granted(Manifest.permission.READ_CONTACTS), granted(Manifest.permission.READ_CALL_LOG)); private set
    override var contacts: List<Contact> = emptyList(); private set
    override var recents: List<RecentCall> = emptyList(); private set
    private var loading: Job? = null
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { reload() }
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    init { reload() }

    override fun listen(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }

    override suspend fun refresh(): ActionResult { reload(); loading?.join(); return ActionResult() }

    private fun reload() {
        if (loading?.isActive == true) return
        loading = scope.launch {
            val contactsOk = granted(Manifest.permission.READ_CONTACTS)
            val logOk = granted(Manifest.permission.READ_CALL_LOG)
            val loadedContacts = if (contactsOk) withContext(Dispatchers.IO) { runCatching { queryContacts(context.contentResolver) }.getOrDefault(emptyList()) } else emptyList()
            val loadedRecents = if (logOk) withContext(Dispatchers.IO) { runCatching { queryRecents(context.contentResolver, loadedContacts) }.getOrDefault(emptyList()) } else emptyList()
            access = PhoneBookAccess(contactsOk, logOk)
            contacts = loadedContacts; recents = loadedRecents
            observe()
            listeners.toList().forEach { it() }
        }
    }

    private fun observe() {
        if (observing) return
        if (!access.contacts && !access.callLog) return
        runCatching {
            if (access.contacts) context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            if (access.callLog) context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            observing = true
        }
    }

    fun close() { if (observing) runCatching { context.contentResolver.unregisterContentObserver(observer) }; observing = false; loading?.cancel() }

    private fun queryContacts(resolver: ContentResolver): List<Contact> {
        data class Row(val id: String, val name: String, val starred: Boolean, val numbers: MutableList<ContactNumber>)
        val rows = linkedMapOf<String, Row>()
        resolver.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY, Phone.STARRED),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val label = Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                val row = rows.getOrPut(id) { Row(id, name, c.getInt(6) == 1, mutableListOf()) }
                if (row.numbers.none { it.number.filter(Char::isDigit) == number.filter(Char::isDigit) })
                    row.numbers += ContactNumber(number, label, c.getInt(5) == 1)
            }
        }
        val nicknames = mutableMapOf<String, MutableList<String>>()
        val relations = mutableMapOf<String, MutableList<String>>()
        val names = rows.values.associate { normalizeName(it.name) to it.id }
        resolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2),
            "${ContactsContract.Data.MIMETYPE} IN (?, ?)", arrayOf(Nickname.CONTENT_ITEM_TYPE, Relation.CONTENT_ITEM_TYPE), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val value = c.getString(2).orEmpty()
                if (c.getString(1) == Nickname.CONTENT_ITEM_TYPE) { if (value.isNotBlank()) nicknames.getOrPut(id) { mutableListOf() } += value; continue }
                addRelation(relations, names, id, c.getInt(3), value)
            }
        }
        // Relations the user set on their own "Me" card ("Mother: Jane Doe") point at the named contact.
        runCatching {
            resolver.query(android.net.Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI, ContactsContract.Contacts.Data.CONTENT_DIRECTORY),
                arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(0) == Relation.CONTENT_ITEM_TYPE) addRelation(relations, names, null, c.getInt(2), c.getString(1).orEmpty())
            }
        }
        return rows.values.filter { it.numbers.isNotEmpty() }.map {
            Contact(it.id, it.name, it.numbers.toList(), it.starred, nicknames[it.id].orEmpty(), relations[it.id].orEmpty().distinct())
        }.sortedBy { normalizeName(it.name) }
    }

    /**
     * A relation row (type T, name N) on contact C: when N names another saved contact, that contact is the user's T;
     * otherwise C itself is read as the user's T (the common "put Mother on Mom's card" habit).
     */
    private fun addRelation(out: MutableMap<String, MutableList<String>>, names: Map<String, String>, owner: String?, type: Int, name: String) {
        val key = when (type) {
            Relation.TYPE_MOTHER -> "mother"; Relation.TYPE_FATHER -> "father"; Relation.TYPE_SPOUSE -> "spouse"
            Relation.TYPE_PARTNER, Relation.TYPE_DOMESTIC_PARTNER -> "partner"; Relation.TYPE_BROTHER -> "brother"; Relation.TYPE_SISTER -> "sister"
            Relation.TYPE_CHILD -> "child"; Relation.TYPE_FRIEND -> "friend"; Relation.TYPE_ASSISTANT -> "assistant"; Relation.TYPE_MANAGER -> "manager"
            Relation.TYPE_PARENT -> "parent"; Relation.TYPE_RELATIVE -> "relative"
            else -> relationOf(name) ?: return
        }
        val target = names[normalizeName(name)]?.takeIf { it != owner } ?: owner ?: return
        out.getOrPut(target) { mutableListOf() } += key
    }

    private fun queryRecents(resolver: ContentResolver, contacts: List<Contact>): List<RecentCall> {
        val projection = mutableListOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.CACHED_NUMBER_TYPE,
            CallLog.Calls.CACHED_NUMBER_LABEL, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        if (Build.VERSION.SDK_INT >= 31) projection += CallLog.Calls.MISSED_REASON
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(CallLog.Calls.DATE))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 120)
        }
        val out = mutableListOf<RecentCall>()
        // QUERY_ARG_LIMIT is not honoured by every provider; CallLogProvider reads the "limit" query parameter.
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "120").build()
        resolver.query(uri, projection.toTypedArray(), args, null)?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(1).orEmpty()
                val cached = c.getString(2).orEmpty()
                val match = findByNumber(contacts, number)
                val kind = when (c.getInt(5)) {
                    CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> RecentKind.Incoming
                    CallLog.Calls.OUTGOING_TYPE -> RecentKind.Outgoing
                    CallLog.Calls.MISSED_TYPE -> RecentKind.Missed
                    CallLog.Calls.REJECTED_TYPE -> RecentKind.Rejected
                    CallLog.Calls.BLOCKED_TYPE -> RecentKind.Blocked
                    CallLog.Calls.VOICEMAIL_TYPE -> RecentKind.Voicemail
                    else -> RecentKind.Other
                }
                val label = match?.second?.label ?: if (cached.isNotBlank()) Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString() else ""
                // MISSED_REASON bit 23 (USER_MISSED_NEVER_RANG): Call Screen or a filter handled it.
                val neverRang = Build.VERSION.SDK_INT >= 31 && (c.getLong(8) and (1L shl 23)) != 0L
                out += RecentCall(c.getString(0).orEmpty(), number, match?.first?.name ?: cached, match != null || cached.isNotBlank(), kind,
                    c.getLong(6), c.getLong(7), label, screened = neverRang)
            }
        }
        return collapseRecents(out).take(60)
    }
}
