package io.lampyr.glasses.data

import android.content.SharedPreferences
import java.text.Normalizer
import java.time.Instant

/**
 * Beeper chat priority on the glasses (BEEP-PRI). The gateway orders /beeper/chats and names each row's tier; this file
 * groups the loaded rows for the list (pins, important, unread, the rest, then one collapsed "Quiet (N)" row), adds the
 * phone-only classifier signal (recent Beeper notifications the gateway rated important), and matches a spoken name to a chat.
 * Everything still works against an older gateway: missing tiers are derived here with the same rules.
 */
enum class BeeperOrder(val wire: String, val label: String) {
    PRIORITY("priority", "Priority"), RECENT("recent", "Recent");

    companion object {
        fun parse(value: Any?): BeeperOrder = entries.find { it.name.equals(value?.toString()?.trim(), true) || it.wire.equals(value?.toString()?.trim(), true) } ?: PRIORITY
    }
}

/** `beeper.*` keys in the `glasses` SharedPreferences file, so `glassesctl pref beeper.order RECENT` works. */
data class BeeperPrefs(val order: BeeperOrder = BeeperOrder.PRIORITY) {
    companion object {
        const val PREFIX = "beeper."
        const val ORDER = "beeper.order"
        fun read(values: Map<String, *>) = BeeperPrefs(order = BeeperOrder.parse(values[ORDER]))
    }
}

interface BeeperPrefStore {
    val prefs: BeeperPrefs
    fun update(next: BeeperPrefs)
    fun listen(listener: () -> Unit): () -> Unit
}

class MemoryBeeperPrefs(initial: BeeperPrefs = BeeperPrefs()) : BeeperPrefStore {
    private val listeners = mutableListOf<() -> Unit>()
    override var prefs = initial
        private set
    override fun update(next: BeeperPrefs) { prefs = next; listeners.toList().forEach { it() } }
    override fun listen(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
}

class SharedBeeperPrefs(private val shared: SharedPreferences) : BeeperPrefStore {
    override val prefs get() = BeeperPrefs.read(shared.all)
    override fun update(next: BeeperPrefs) { shared.edit().putString(BeeperPrefs.ORDER, next.order.name).apply() }
    override fun listen(listener: () -> Unit): () -> Unit {
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == null || key.startsWith(BeeperPrefs.PREFIX)) listener() }
        shared.registerOnSharedPreferenceChangeListener(callback)
        return { shared.unregisterOnSharedPreferenceChangeListener(callback) }
    }
}

/** A chat's list tier. */
enum class ChatTier { PINNED, IMPORTANT, UNREAD, QUIET, READ }

/** The loaded chats grouped for the list. [quiet] is shown as one collapsed row at the bottom (PRIORITY only). */
data class BeeperGroups(val top: List<Chat>, val quiet: List<Chat>) {
    val all get() = top + quiet
}

object BeeperPriority {
    /** A counting unread chat at or above this score is listed with the important ones. */
    const val IMPORTANT = 0.5
    /** A Beeper notification the classifier rated at least this important, for this chat within [CLASSIFIED_WINDOW_MS], lifts it. */
    const val CLASSIFIED_IMPORTANT = 0.7
    const val CLASSIFIED_WINDOW_MS = 6 * 3600_000L

    val Chat.hasUnread get() = unread > 0 || markedUnread
    val Chat.isPinned get() = pinned || glassesPinned

    /** The same deterministic score the gateway computes from the row alone (used when the gateway sent none). */
    fun localScore(c: Chat): Double {
        var score = 0.0
        if (c.mentions > 0) score += 0.5
        val last = c.last
        val theirs = last != null && !last.mine && last.sender != "You"
        if (theirs && asksQuestion(last!!.text)) score += if (c.isGroup || c.type == "group") 0.1 else 0.3
        if (!(c.isGroup || c.type == "group") && !Regex("^\\+?\\d{3,13}$").matches(c.name.trim())) score += 0.2
        return minOf(1.0, score)
    }

    fun asksQuestion(text: String): Boolean = Regex("\\?(\\s|$)").containsMatchIn(text) ||
        Regex("^(can|could|would|will|are|do|did|have|is|when|where|what|who|how|why|you)\\b.*\\byou\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())

    /** Highest importance the classifier gave a recent Beeper notification for this chat (matched by chat ID, else by name). */
    fun classifiedImportance(c: Chat, notifications: List<Notification>, nowMs: Long): Double = notifications
        .filter { (it.icon == "beeper" || it.source.contains("beeper", true)) && nowMs - it.at in 0..CLASSIFIED_WINDOW_MS }
        .filter { n -> n.open?.args?.get("chatId") == c.id || n.open?.args?.get("target") == c.id || n.title.trim().equals(c.name.trim(), true) }
        .maxOfOrNull { it.importance } ?: 0.0

    fun score(c: Chat, notifications: List<Notification> = emptyList(), nowMs: Long = 0): Double {
        val base = if (c.priority.isNotEmpty()) c.importance else localScore(c)
        val classified = classifiedImportance(c, notifications, nowMs)
        return maxOf(base, if (classified >= CLASSIFIED_IMPORTANT) classified else 0.0)
    }

    fun tier(c: Chat, notifications: List<Notification> = emptyList(), nowMs: Long = 0): ChatTier = when {
        c.isPinned || c.priority == "pinned" -> ChatTier.PINNED
        c.countsTowardUnread -> if (c.priority == "important" || score(c, notifications, nowMs) >= IMPORTANT) ChatTier.IMPORTANT else ChatTier.UNREAD
        c.hasUnread -> ChatTier.QUIET
        else -> ChatTier.READ
    }

    private fun epoch(c: Chat) = runCatching { Instant.parse(c.lastActivity).toEpochMilli() }.getOrNull()

    /**
     * PRIORITY: pins, important (highest score first), other unread, then the rest in the given (gateway) order, with
     * the quiet unread chats returned separately for the collapsed row. RECENT: pins first, then the given order, no
     * collapsing. Ties keep the given order, so the gateway's recency survives (and rows without a time stay put).
     */
    fun group(chats: List<Chat>, order: BeeperOrder, notifications: List<Notification> = emptyList(), nowMs: Long = 0): BeeperGroups {
        val tiers = chats.associate { it.id to tier(it, notifications, nowMs) }
        val pins = chats.filter { tiers[it.id] == ChatTier.PINNED }
        if (order == BeeperOrder.RECENT) return BeeperGroups(pins + chats.filter { tiers[it.id] != ChatTier.PINNED }, emptyList())
        val important = chats.filter { tiers[it.id] == ChatTier.IMPORTANT }
            .withIndex().sortedWith(compareByDescending<IndexedValue<Chat>> { score(it.value, notifications, nowMs) }.thenBy { it.index }).map { it.value }
        val unread = chats.filter { tiers[it.id] == ChatTier.UNREAD }
        val read = chats.filter { tiers[it.id] == ChatTier.READ }
        val quiet = chats.filter { tiers[it.id] == ChatTier.QUIET }
        return BeeperGroups(pins + important + unread + read, quiet)
    }

    // ---- Find a chat by a spoken name (same rules as the gateway's lib/beeper-priority.mjs) ----
    private val FILLER = Regex("^(?:(?:please|hey|ok(?:ay)?|um+|uh+)\\s+)*(?:(?:find|open|show|go\\s+to|jump\\s+to|search(?:\\s+for)?|get|pull\\s+up)\\s+)?(?:(?:my|the)\\s+)?(?:(?:chat|conversation|messages?|thread|group(?:\\s+chat)?|dm)\\s+(?:with|from|for)\\s+)?", RegexOption.IGNORE_CASE)
    private val TAIL = Regex("\\s+(?:chat|conversation|group(?:\\s+chat)?|messages?|thread|on\\s+\\w+)$", RegexOption.IGNORE_CASE)

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun cleanQuery(value: String): String = normalize(value.trim().replace(Regex("[.!?,]+$"), "").replace(FILLER, "").replace(TAIL, ""))

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1); row[0] = i
            for (j in 1..b.length) row[j] = minOf(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = row
        }
        return prev[b.length]
    }
    private fun similar(a: String, b: String) = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    private fun round(v: Double, places: Int = 2): Double { var f = 1.0; repeat(places) { f *= 10 }; return Math.round(v * f) / f }

    /** 0..1: how well a spoken query names [candidate]. */
    fun nameScore(query: String, candidate: String): Double {
        val q = normalize(query); val n = normalize(candidate)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        if (q == n) return 1.0
        val qc = q.replace(" ", ""); val nc = n.replace(" ", "")
        if (qc == nc) return 0.98
        val qt = q.split(' '); val nt = n.split(' ')
        if (nc.startsWith(qc) && qc.length >= 3) return 0.9
        if (qt.all { t -> nt.any { it.startsWith(t) } } && qc.length >= 2) return 0.86
        val exact = qt.count { it in nt }
        if (exact > 0) return 0.6 + 0.2 * exact / qt.size
        if (qc.length >= 4 && nc.contains(qc)) return 0.7
        val fuzzy = qt.sumOf { t -> nt.maxOf { similar(t, it) } } / qt.size
        val best = maxOf(fuzzy, similar(qc, nc))
        return if (best >= 0.7) round(best * 0.8) else 0.0
    }

    data class Match(val chat: Chat, val score: Double, val on: String)

    /**
     * Best matching loaded chats for a spoken name. [nicknames] maps a contact's display name to its nicknames: a
     * spoken nickname also searches for that contact's name. Ties favour pinned and unread chats, then list order.
     */
    fun find(query: String, chats: List<Chat>, nicknames: Map<String, List<String>> = emptyMap(), max: Int = 5): List<Match> {
        val q = cleanQuery(query)
        if (q.isEmpty()) return emptyList()
        val aliases = nicknames.entries.filter { (_, nicks) -> nicks.any { nameScore(q, it) >= 0.9 } }.map { it.key }
        val queries = listOf(q) + aliases.take(3).map(::normalize)
        return chats.withIndex().mapNotNull { (index, c) ->
            val direct = !(c.isGroup || c.type == "group")
            var best = 0.0; var on = ""
            for (term in queries) {
                val alias = term != q
                fun consider(name: String, weight: Double) {
                    val s = nameScore(term, name) * weight * (if (alias) 0.95 else 1.0)
                    if (s > best) { best = s; on = name }
                }
                consider(c.name, 1.0)
                c.participants.names.forEach { consider(it, if (direct) 0.97 else 0.85) }
            }
            if (best < 0.5) null
            else IndexedValue(index, Match(c, minOf(1.0, round(best + (if (c.isPinned) 0.02 else 0.0) + (if (c.hasUnread && !c.spam) 0.01 else 0.0), 3)), on))
        }.sortedWith(compareByDescending<IndexedValue<Match>> { it.value.score }.thenBy { it.index }).take(max).map { it.value }
    }

    /** The first match is a clear answer: good on its own and well ahead of the second. */
    fun confident(matches: List<Match>) = matches.isNotEmpty() && matches[0].score >= 0.8 && (matches.size == 1 || matches[0].score - matches[1].score >= 0.08)
}
