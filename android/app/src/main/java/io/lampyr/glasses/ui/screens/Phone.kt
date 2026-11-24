package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.calls.*
import io.lampyr.glasses.data.ActionResult
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*

/**
 * The glasses Phone app (requirement 6): Favorites, Recents and Contacts tabs (◀▶ switch tab on every tab; X is
 * the next tab, or the next letter on Contacts), hold L to say a name, and one deliberate Y on the "Call Mom?" card
 * before anything is dialled. Matching happens on the phone.
 */
enum class PhoneTab(val label: String) { Favorites("Favorites"), Recents("Recents"), Contacts("Contacts") }

/** "missed 33m" / "out 2h": the Recents right column. */
fun recentRight(r: RecentCall, nowMs: Long): String {
    val kind = when {
        r.screened && !r.known -> "screened"
        else -> when (r.kind) {
            RecentKind.Incoming -> "in"; RecentKind.Outgoing -> "out"; RecentKind.Missed -> "missed"
            RecentKind.Rejected -> "declined"; RecentKind.Blocked -> "blocked"; RecentKind.Voicemail -> "voicemail"; RecentKind.Other -> "call"
        }
    }
    return "$kind ${age(((nowMs - r.atMs) / 1000).coerceAtLeast(0))}"
}

/** Opens the confirm card for a contact, or its number list when it has several numbers and none is primary. */
fun callContact(ui: Ui, contact: Contact) {
    val number = chooseNumber(contact, null)
    if (number != null) ui.push(PhoneConfirm(ui, Callee(contact.name, number.number, number.label, contact)))
    else ui.push(PhoneNumbers(ui, contact))
}

class PhoneRoot private constructor(ui: Ui, start: PhoneTab, private val holder: RowsHolder) : ListView(ui, "phone", items = { holder.rows() }) {
    constructor(ui: Ui, start: PhoneTab = PhoneTab.Favorites) : this(ui, start, RowsHolder())
    /** ListView takes its rows in the constructor; the tab state lives here, so the lambda is bound in init. */
    class RowsHolder { var rows: () -> List<Row> = { emptyList() } }
    var tab = start
        private set
    private val book get() = ui.data.calls.book
    private var unsubscribe: () -> Unit = {}

    private fun tabRows(): List<Row> = when (tab) {
        PhoneTab.Favorites -> {
            val favs = favorites(book)
            if (!book.access.contacts) listOf(Row("Allow Contacts in phone setup"))
            else if (favs.isEmpty()) listOf(Row("Star contacts to add them"))
            else {
                val out = mutableListOf<Row>()
                favs.forEachIndexed { i, (c, starred) ->
                    if (!starred && (i == 0 || favs[i - 1].second)) out += Row("Frequent", sep = true)
                    out += Row(c.name, right = chooseNumber(c, null)?.label?.let(::shortLabel) ?: "${c.numbers.size} numbers",
                        open = { callContact(ui, c) }, selLabel = "Call ${c.name}")
                }
                out
            }
        }
        PhoneTab.Recents -> {
            val recents = book.recents
            if (!book.access.callLog) listOf(Row("Allow Call log in phone setup"))
            else if (recents.isEmpty()) listOf(Row("No recent calls"))
            else recents.take(40).map { r ->
                val name = r.name.ifBlank { r.number.ifBlank { "Unknown" } }
                Row(if (r.count > 1) "$name (${r.count})" else name, right = recentRight(r, ui.nowMs()),
                    open = if (r.number.isBlank()) null else ({ ui.push(PhoneConfirm(ui, Callee(name, r.number, r.label))) }),
                    selLabel = "Call back")
            }
        }
        PhoneTab.Contacts -> {
            val all = book.contacts
            if (!book.access.contacts) listOf(Row("Allow Contacts in phone setup"))
            else if (all.isEmpty()) listOf(Row("No contacts with numbers"))
            else {
                val out = mutableListOf<Row>()
                var letter = ""
                all.forEach { c ->
                    val initial = initialOf(c.name)
                    if (initial != letter) { letter = initial; out += Row(initial, sep = true) }
                    out += Row(c.name, right = if (c.numbers.size > 1) "${c.numbers.size} numbers" else shortLabel(c.numbers.first().label),
                        open = { callContact(ui, c) }, selLabel = "Call ${c.name}")
                }
                out
            }
        }
    }

    private fun hintFor() = when (tab) {
        PhoneTab.Favorites -> "Y call · ◀▶ tab · L say a name"
        PhoneTab.Recents -> "Y call · ◀▶ tab · L say a name"
        PhoneTab.Contacts -> "Y open · ◀▶ tab · X letter"
    }

    /** ◀▶ always switch tab (they never page or jump inside one); X is the Contacts letter jump. */
    private fun tabLegend() = mapOf("act" to if (tab == PhoneTab.Contacts) "Next letter" else "Next tab",
        "left" to "Previous tab", "right" to "Next tab")

    init {
        holder.rows = { tabRows() }
        cur = rows().indexOfFirst { !it.sep }.coerceAtLeast(0)
        hint = hintFor()
        animateChrome = false
        textTitle = { tab.label }
        textHint = { when (tab) { PhoneTab.Favorites -> "Y call <> tab L say"; PhoneTab.Recents -> "Y call <> tab L say"; PhoneTab.Contacts -> "Y open <> tab X A-Z" } }
        refreshHook = { book.refresh() }
        leg = tabLegend() + mapOf("talkH" to "Say a name to call")
        target = TalkTarget("Call who?", "raw", send = { text -> search(text) }, instant = true)
        onKey = { k, _ ->
            when (k) {
                "left", "right" -> { switch(if (k == "right") 1 else -1); true }
                else -> false
            }
        }
        act = { if (tab == PhoneTab.Contacts) jumpLetter(1, wrap = true) else switch(1) }
    }

    override fun onOpen() {
        super.onOpen()
        unsubscribe = book.listen { ui.requestRender() }
    }

    override fun onClose() { unsubscribe() }

    fun switch(delta: Int) {
        tab = PhoneTab.entries[(tab.ordinal + delta).mod(PhoneTab.entries.size)]
        top = 0; cur = rows().indexOfFirst { !it.sep }.coerceAtLeast(0)
        hint = hintFor()
        leg = leg + tabLegend()
        ui.requestRender()
    }

    /** Contacts: X jumps to the next initial letter ([wrap]: after the last letter comes the first again). */
    fun jumpLetter(delta: Int, wrap: Boolean = false) {
        val rows = rows()
        val heads = rows.indices.filter { rows[it].sep }
        if (heads.isEmpty()) return
        val current = heads.lastOrNull { it < cur } ?: heads.first()
        val at = heads.indexOf(current)
        val next = heads.getOrNull(if (wrap) (at + delta).mod(heads.size) else at + delta) ?: return
        cur = (next + 1).coerceAtMost(rows.lastIndex)
        if (delta < 0 || cur < top) top = next
    }

    override fun talkTap(): Boolean {
        if (ui.data.calls.snapshot.calls.isNotEmpty()) { ui.flash("Microphone reserved for phone call"); return true }
        return false
    }

    private fun search(text: String): ActionResult = when (val r = resolveCallee(parseCallQuery(text), book.contacts)) {
        is Resolution.Found -> { ui.push(PhoneConfirm(ui, r.callee)); ActionResult() }
        is Resolution.Ambiguous -> { ui.push(PhoneSearch(ui, text, r.candidates)); ActionResult() }
        is Resolution.NotFound -> ActionResult.failure(r.message)
    }

    override fun draw(g: Gfx, t: Double) {
        with(g) { frame(ui, "Phone · ${tab.label}", "phone", 0.0, railText = listOf("Tab ${tab.ordinal + 1}/3")) }
        super.draw(g, t)
    }
}

/** Several numbers for one contact: pick which. */
class PhoneNumbers(ui: Ui, val contact: Contact) : ListView(ui, "phone-numbers", items = {
    contact.numbers.map { n -> Row(shortLabel(n.label).replaceFirstChar { it.uppercase() }, right = n.number,
        open = { ui.replaceTop(PhoneConfirm(ui, Callee(contact.name, n.number, n.label, contact))) }, selLabel = "Call") }
}, chrome = { frame(ui, contact.name, "phone", 0.0, railText = listOf("Numbers")) }, hint = "Y call · B back") {
    init {
        animateChrome = false; textTitle = { contact.name }; textHint = { "Y call B back" }
        // Text grids: the whole number is the label (a right badge is capped at half the width).
        textItems = { rows().mapIndexed { i, r -> r.copy(label = contact.numbers[i].number, right = r.label.lowercase()) } }
    }
}

/** Voice search results when the name was not a clear match. */
class PhoneSearch(ui: Ui, val query: String, val candidates: List<Callee>) : ListView(ui, "phone-search", items = {
    candidates.map { c -> Row(c.name, right = shortLabel(c.label), open = { ui.replaceTop(PhoneConfirm(ui, c)) }, selLabel = "Call") }
}, chrome = { frame(ui, "Call who?", "phone", 0.0, railText = listOf("Matches")) }, hint = "Y call · B back") {
    init { animateChrome = false; textTitle = { "Call who?" }; textHint = { "Y call B back" } }
}

/**
 * The one deliberate press before any call is placed from the glasses ("Call Mom?  mobile"). Y places the call
 * (TelecomManager); the Ui swaps this card for the call screen as soon as the Dialing call appears. Also used by
 * the assistant's call.place when the confirm policy asks (then [onResult] reports what happened; null = cancelled).
 */
class PhoneConfirm(
    ui: Ui,
    val callee: Callee,
    val route: String? = null,
    val fromJoy: Boolean = false,
    private val onResult: ((ActionResult?) -> Unit)? = null,
) : Screen(ui, "phone-confirm", "phone-confirm") {
    var placing = false
        private set
    private var answered = false
    /** the assistant's card after its wait ended (timeout/cancel): Y must never dial from it, even if it resurfaces later. */
    var expired = false
        private set
    private var openedAt = Double.NaN
    /** Lit while the user decides, but never forever: after [AWAKE_S] the normal idle fade applies (any button wakes). */
    override val keepAwake get() = !expired && (openedAt.isNaN() || ui.nowS() - openedAt < AWAKE_S)
    override val needsAnimation get() = false
    private val title get() = "Call ${callee.name}?"
    private val detail get() = listOf(shortLabel(callee.label).takeIf { callee.label.isNotBlank() }, callee.number).filterNotNull().joinToString(" · ")

    private fun decide(result: ActionResult?) { if (!answered) { answered = true; onResult?.invoke(result) } }

    override fun onOpen() { if (openedAt.isNaN()) openedAt = ui.nowS() }

    /**
     * Ends the assistant's wait: the card can no longer place a call and leaves the stack. A card saved behind an incoming-call
     * takeover is not on the stack, so it is only marked and closes itself on the next press.
     */
    fun expire() {
        if (expired) return
        expired = true
        if (placing) return
        if (ui.stack.lastOrNull() === this) ui.pop() else if (ui.stack.remove(this)) onClose()
        ui.requestRender()
    }

    fun confirm() {
        if (placing) return
        if (expired || (fromJoy && answered)) { if (ui.top === this) ui.pop(); ui.flash("That call request expired"); return }
        placing = true
        ui.expectOutgoing()
        ui.requestRender()
        ui.perform(after = { decide(it) }) {
            val result = ui.data.calls.place(callee.number, route)
            if (!result.ok) { placing = false; ui.cancelExpectOutgoing(); decide(result); if (ui.top === this) ui.pop() }
            result
        }
    }

    override fun key(k: String): Boolean {
        when (k) {
            "sel" -> confirm()
            "back" -> { decide(null); ui.pop() }
            "act", "plus", "up", "down", "left", "right" -> {} // A stray press never acts underneath.
            else -> return false
        }
        return true
    }

    /** Closing before Y is a cancel; once placing, the Ui swaps this card for the call screen (not a cancel). */
    override fun onClose() { if (!placing) decide(null) }

    override fun legend() = mapOf("sel" to "Call ${callee.name}", "back" to "Cancel")

    override fun agentText() = "$title\n$detail"

    override fun draw(g: Gfx, t: Double) = with(g) {
        frame(ui, if (fromJoy) "${io.lampyr.glasses.settings.Names.assistant} wants to call" else "Call?", "call-phone", 0.0,
            railText = listOf(shortLabel(callee.label).takeIf { callee.label.isNotBlank() }.orEmpty()))
        var y = TOP + 4
        pages(title, CW - 12, 2, "l").first().forEach { drawLine(it, CX, y, "l"); y += lh("l") }
        y = TOP + 2 * lh("l") + 12
        tx(fit(detail, CW - 12), CX, y)
        if (placing) tx("Calling…", CX, y + lh() + 6, tone = SECONDARY)
        else if (expired) tx("Request expired", CX, y + lh() + 6, tone = SECONDARY)
        hintRow(if (placing || expired) "B close" else "Y call · B cancel", safety = true)
    }

    companion object { const val AWAKE_S = 30.0 }

    override fun drawText(tg: TextGrid) {
        val body = listOf(callee.name, detail, if (placing) "Calling..." else if (expired) "Request expired" else "").filter { it.isNotBlank() }.joinToString("\n")
        TextChrome.reader(tg, if (fromJoy) "${io.lampyr.glasses.settings.Names.assistant}: call?" else "Call?", "", gridPages(body, tg.cols, tg.rows - 2), 0,
            if (placing || expired) "B close" else "Y call B cancel")
    }
}
