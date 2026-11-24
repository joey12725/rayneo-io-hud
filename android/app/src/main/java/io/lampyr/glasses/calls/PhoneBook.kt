package io.lampyr.glasses.calls

import io.lampyr.glasses.data.ActionResult
import java.text.Normalizer
import java.util.Locale

/**
 * Contacts and call log for the glasses Phone app (requirement 6). Platform-free so ranking, favourites and the
 * "call mom" resolution are unit-tested; [AndroidPhoneBook] reads ContactsContract/CallLog, [FakePhoneBook] is the
 * offline demo. Matching always happens on the phone: the contact list never leaves it for matching.
 */
data class ContactNumber(val number: String, val label: String, val primary: Boolean = false)
data class Contact(
    val id: String,
    val name: String,
    val numbers: List<ContactNumber>,
    val starred: Boolean = false,
    /** Nickname rows ("Mom", "Bud"). */
    val nicknames: List<String> = emptyList(),
    /** Relationship words that point at this contact ("mother", "brother"); see [RELATIONS]. */
    val relations: List<String> = emptyList(),
)
data class ContactMatch(val contact: Contact, val score: Double)
enum class RecentKind { Incoming, Outgoing, Missed, Rejected, Blocked, Voicemail, Other }
data class RecentCall(
    val id: String,
    val number: String,
    val name: String,
    val known: Boolean,
    val kind: RecentKind,
    val atMs: Long,
    val durationS: Long = 0,
    val label: String = "",
    val count: Int = 1,
    /** Never rang: Call Screen or a filter handled it (dimmed "Screened" in Recents). */
    val screened: Boolean = false,
)
data class PhoneBookAccess(val contacts: Boolean, val callLog: Boolean)

interface PhoneBook {
    val access: PhoneBookAccess
    /** All contacts with at least one number, sorted by name. */
    val contacts: List<Contact>
    /** Newest first, consecutive rows for the same number and kind collapsed into [RecentCall.count]. */
    val recents: List<RecentCall>
    suspend fun refresh(): ActionResult = ActionResult()
    fun listen(listener: () -> Unit): () -> Unit = {}

    companion object {
        val NONE: PhoneBook = object : PhoneBook {
            override val access = PhoneBookAccess(false, false)
            override val contacts = emptyList<Contact>()
            override val recents = emptyList<RecentCall>()
        }
    }
}

/** A callee a request resolved to: one contact number, or a raw number. */
data class Callee(val name: String, val number: String, val label: String = "", val contact: Contact? = null)
sealed class Resolution {
    data class Found(val callee: Callee) : Resolution()
    /** Several people match, or one person with several numbers and no label: ask which. */
    data class Ambiguous(val candidates: List<Callee>) : Resolution()
    data class NotFound(val message: String) : Resolution()
}
data class CallQuery(val name: String, val label: String? = null, val number: String? = null)

/** Relationship synonyms: what the user says -> the words stored on the contact (nickname, relation type or name). */
val RELATIONS: Map<String, Set<String>> = mapOf(
    "mother" to setOf("mom", "mum", "mommy", "mama", "ma", "mother"),
    "father" to setOf("dad", "daddy", "papa", "pa", "father", "pop"),
    "spouse" to setOf("wife", "husband", "spouse"),
    "partner" to setOf("partner", "girlfriend", "boyfriend"),
    "brother" to setOf("brother", "bro"),
    "sister" to setOf("sister", "sis"),
    "child" to setOf("son", "daughter", "kid", "child"),
    "grandmother" to setOf("grandma", "granny", "nana", "grandmother"),
    "grandfather" to setOf("grandpa", "granddad", "grandfather"),
    "friend" to setOf("friend"),
    "assistant" to setOf("assistant"),
    "manager" to setOf("boss", "manager"),
)

/** The relation key ("mother") a spoken word ("mom") means, or null. */
fun relationOf(word: String): String? {
    val w = normalizeName(word)
    return RELATIONS.entries.firstOrNull { (key, words) -> w == key || w in words }?.key
}

fun normalizeName(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.US).replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()

private val LABEL_WORDS = mapOf(
    "mobile" to "mobile", "cell" to "mobile", "cellphone" to "mobile", "iphone" to "mobile",
    "work" to "work", "office" to "work", "home" to "home", "house" to "home", "main" to "main", "other" to "other",
)

/** "call mom on her cell" -> name "mom", label "mobile"; "dial 312 555 0142" -> number. */
fun parseCallQuery(text: String): CallQuery {
    var t = text.trim().trimEnd('.', '!', '?', ',').trim()
    val names = listOf(io.lampyr.glasses.settings.Names.assistant, "assistant").filter { it.isNotBlank() }.joinToString("|") { Regex.escape(it) }
    t = t.replace(Regex("^(?:(?:hey|ok|okay)\\s+)?(?:(?:$names)[, ]+)?(?:please\\s+)?(?:can you\\s+|could you\\s+)?(?:call|dial|phone|ring|give)\\s+", RegexOption.IGNORE_CASE), "")
    t = t.replace(Regex("\\s+(?:a call|a ring)$", RegexOption.IGNORE_CASE), "")
    t = t.replace(Regex("\\s+(?:please|now|for me)$", RegexOption.IGNORE_CASE), "")
    val digits = t.filter { it.isDigit() }
    if (digits.length >= 3 && t.all { it.isDigit() || it in " +-()." }) return CallQuery("", number = t.trim())
    var label: String? = null
    val m = Regex("\\s+(?:on\\s+(?:(?:his|her|their|the)\\s+)?|at\\s+(?:(?:his|her|their|the)\\s+)?)?(mobile|cell(?:phone)?|iphone|work|office|home|house|main|other)(?:\\s+(?:phone|number|line))?$", RegexOption.IGNORE_CASE).find(t)
    if (m != null && m.range.first > 0) { label = LABEL_WORDS[m.groupValues[1].lowercase()]; t = t.substring(0, m.range.first) }
    t = t.replace(Regex("^(?:my|our)\\s+", RegexOption.IGNORE_CASE), "")
    return CallQuery(t.trim(), label)
}

private fun distance(a: String, b: String): Int {
    if (a == b) return 0
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1); cur[0] = i
        for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = cur
    }
    return prev[b.length]
}

/** Score one contact for a spoken name (1.0 exact). Speech-to-text misspells names, so near misses still score. */
fun contactScore(query: String, contact: Contact): Double {
    val q = normalizeName(query)
    if (q.isEmpty()) return 0.0
    val name = normalizeName(contact.name)
    val relation = relationOf(q)
    var best = 0.0
    fun take(v: Double) { if (v > best) best = v }
    if (name == q) take(1.0)
    contact.nicknames.forEach { if (normalizeName(it) == q) take(0.99) }
    if (relation != null && (relation in contact.relations || contact.nicknames.any { relationOf(it) == relation } || relationOf(name) == relation)) take(0.98)
    val tokens = name.split(' ').filter { it.isNotEmpty() }
    val qTokens = q.split(' ').filter { it.isNotEmpty() }
    if (qTokens.size > 1 && qTokens.all { qt -> tokens.any { it == qt } }) take(0.95)
    if (qTokens.size == 1 && tokens.firstOrNull() == q) take(0.9)
    if (qTokens.size == 1 && tokens.any { it == q }) take(0.85)
    if (qTokens.all { qt -> tokens.any { it.startsWith(qt) } } && q.length >= 2) take(0.75)
    if (q.length >= 4) {
        val d = minOf(distance(q, name), tokens.minOfOrNull { distance(q, it) } ?: 99)
        if (d == 1) take(0.7) else if (d == 2 && q.length >= 6) take(0.6)
    }
    if (q.length >= 3 && name.contains(q)) take(0.55)
    if (best > 0 && contact.starred) best += 0.02
    return best
}

/** Ranked matches above the noise floor, best first (starred contacts win ties). */
fun rankContacts(query: String, contacts: List<Contact>, limit: Int = 8): List<ContactMatch> =
    contacts.map { ContactMatch(it, contactScore(query, it)) }.filter { it.score >= 0.5 }
        .sortedWith(compareByDescending<ContactMatch> { it.score }.thenBy { it.contact.name }).take(limit)

private fun labelMatches(number: ContactNumber, label: String) = normalizeName(number.label).let { l ->
    l == label || (label == "mobile" && l in setOf("cell", "mobile", "iphone")) || l.startsWith(label)
}

/** Which number of [contact] to call: the labelled one, else the only/primary/mobile one; null = ask. */
fun chooseNumber(contact: Contact, label: String?): ContactNumber? {
    val numbers = contact.numbers.distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
    if (label != null) return numbers.firstOrNull { labelMatches(it, label) }
    return numbers.singleOrNull() ?: numbers.firstOrNull { it.primary } ?: numbers.filter { labelMatches(it, "mobile") }.singleOrNull()
}

/** Contact for a phone number (last 10 digits), for caller names in Recents and outgoing calls. */
fun findByNumber(contacts: List<Contact>, number: String): Pair<Contact, ContactNumber>? {
    val key = number.filter(Char::isDigit).takeLast(10)
    if (key.length < 4) return null
    contacts.forEach { c -> c.numbers.firstOrNull { it.number.filter(Char::isDigit).takeLast(10) == key }?.let { return c to it } }
    return null
}

/**
 * "call mom" -> one number, candidates or not found. A clear winner (top score >= 0.9 and at least 0.05 ahead of
 * the next person) is used; otherwise everyone close is a candidate. A person with several numbers and no label is
 * ambiguous unless one number is primary or the only mobile.
 */
fun resolveCallee(query: CallQuery, contacts: List<Contact>): Resolution {
    query.number?.let { raw ->
        val (digits, reason) = dialable(raw)
        if (digits == null) return Resolution.NotFound(reason ?: "Not a phone number")
        val known = findByNumber(contacts, digits)
        return Resolution.Found(Callee(known?.first?.name ?: raw.trim(), digits, known?.second?.label.orEmpty(), known?.first))
    }
    if (query.name.isBlank()) return Resolution.NotFound("Say a name or a number")
    val ranked = rankContacts(query.name, contacts)
    if (ranked.isEmpty()) return Resolution.NotFound("No contact named \"${query.name}\"")
    val top = ranked.first()
    val clear = top.score >= 0.9 && (ranked.getOrNull(1)?.score ?: 0.0) < top.score - 0.05
    if (!clear) return Resolution.Ambiguous(ranked.filter { it.score >= top.score - 0.1 }.take(6).flatMap { m -> callees(m.contact, query.label) })
    val number = chooseNumber(top.contact, query.label)
        ?: return if (query.label != null && top.contact.numbers.none { labelMatches(it, query.label) })
            Resolution.NotFound("${top.contact.name} has no ${query.label} number")
        else Resolution.Ambiguous(callees(top.contact, null))
    return Resolution.Found(Callee(top.contact.name, number.number, number.label, top.contact))
}

private fun callees(contact: Contact, label: String?) =
    contact.numbers.filter { label == null || labelMatches(it, label) }.ifEmpty { contact.numbers }
        .map { Callee(contact.name, it.number, it.label, contact) }

/**
 * Favorites tab: starred contacts (A-Z), padded with the people the user calls most (outgoing and answered calls in the
 * call log) up to [size]. The user has only three starred contacts, so padding keeps the tab useful.
 */
fun favorites(book: PhoneBook, size: Int = 8): List<Pair<Contact, Boolean>> {
    val starred = book.contacts.filter { it.starred }.sortedBy { normalizeName(it.name) }
    if (starred.size >= size) return starred.take(size).map { it to true }
    val counts = linkedMapOf<String, Int>()
    book.recents.filter { it.kind == RecentKind.Outgoing || it.kind == RecentKind.Incoming }.forEach { r ->
        findByNumber(book.contacts, r.number)?.first?.takeIf { !it.starred }?.let { counts[it.id] = (counts[it.id] ?: 0) + r.count }
    }
    val frequent = counts.entries.sortedByDescending { it.value }.mapNotNull { e -> book.contacts.find { it.id == e.key } }
    return starred.map { it to true } + frequent.take(size - starred.size).map { it to false }
}

/** Collapses consecutive rows (newest first) for the same number and kind into one row with a count. */
fun collapseRecents(rows: List<RecentCall>): List<RecentCall> {
    val out = mutableListOf<RecentCall>()
    rows.forEach { r ->
        val last = out.lastOrNull()
        if (last != null && last.kind == r.kind && last.number.filter(Char::isDigit).takeLast(10) == r.number.filter(Char::isDigit).takeLast(10))
            out[out.lastIndex] = last.copy(count = last.count + r.count, screened = last.screened && r.screened)
        else out += r
    }
    return out
}

/** "mobile", "work"; Android type labels arrive capitalised. */
fun shortLabel(label: String): String = label.trim().lowercase(Locale.US).ifBlank { "phone" }

/** Initial used for the A-Z jump ("#" for names that start with a digit or symbol). */
fun initialOf(name: String): String = normalizeName(name).firstOrNull()?.takeIf { it.isLetter() }?.uppercaseChar()?.toString() ?: "#"

/** Offline demo phone book shared with the simulator (fictional people and 555 numbers). */
class FakePhoneBook(private val nowMs: () -> Long = { System.currentTimeMillis() }) : PhoneBook {
    override var access = PhoneBookAccess(true, true)
    override var contacts: List<Contact> = listOf(
        Contact("c-alex", "Alex Chen", listOf(ContactNumber("(773) 555-0188", "Mobile"))),
        Contact("c-ben", "Ben Ortiz", listOf(ContactNumber("(312) 555-0110", "Mobile"), ContactNumber("(312) 555-0111", "Work"))),
        Contact("c-dad", "Dad", listOf(ContactNumber("(630) 555-0102", "Mobile")), starred = true, relations = listOf("father")),
        Contact("c-dana", "Dana Park", listOf(ContactNumber("(872) 555-0133", "Mobile"))),
        Contact("c-marcus", "Marcus Lee", listOf(ContactNumber("(312) 555-0166", "Mobile"))),
        Contact("c-mom", "Mom", listOf(ContactNumber("(630) 555-0101", "Mobile", primary = true), ContactNumber("(630) 555-0100", "Home")),
            starred = true, relations = listOf("mother")),
        Contact("c-priya", "Priya Shah", listOf(ContactNumber("(224) 555-0175", "Work"))),
        Contact("c-sam", "Sam Rivera", listOf(ContactNumber("(312) 555-0142", "Mobile")), starred = true),
        Contact("c-tess", "Tess Nguyen", listOf(ContactNumber("(708) 555-0123", "Mobile"))),
    )
        private set
    private val minute = 60_000L
    override val recents: List<RecentCall>
        get() = listOf(
            RecentCall("r1", "(312) 555-0142", "Sam Rivera", true, RecentKind.Missed, nowMs() - 33 * minute, label = "Mobile"),
            RecentCall("r2", "(630) 555-0101", "Mom", true, RecentKind.Outgoing, nowMs() - 2 * 60 * minute, 754, "Mobile"),
            RecentCall("r3", "(872) 555-0199", "", false, RecentKind.Missed, nowMs() - 5 * 60 * minute, screened = true),
            RecentCall("r4", "(773) 555-0188", "Alex Chen", true, RecentKind.Incoming, nowMs() - 26 * 60 * minute, 312, "Mobile", count = 2),
            RecentCall("r5", "(312) 555-0166", "Marcus Lee", true, RecentKind.Outgoing, nowMs() - 50 * 60 * minute, 95, "Mobile", count = 2),
            RecentCall("r6", "(224) 555-0175", "Priya Shah", true, RecentKind.Rejected, nowMs() - 75 * 60 * minute, label = "Work"),
        )
    fun replace(next: List<Contact>) { contacts = next }
}
