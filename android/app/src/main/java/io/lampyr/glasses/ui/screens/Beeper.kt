package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import kotlin.math.*

/** Right-hand mark of a chat row: spam and muted chats show their mark instead of a count (they never count). */
fun chatMark(c: Chat): String = when {
    c.spam -> "spam"
    c.muted && c.mentions <= 0 -> "muted"
    c.unread > 0 -> "${c.unread} new"
    c.markedUnread -> "new"
    else -> ""
}

/** Registers ui.openTarget("beeper", chatId): the thread for a chat ID, or the chat list without one. */
fun registerBeeperTarget(ui: Ui) {
    ui.targetOpeners["beeper"] = { target ->
        if (target.isNullOrBlank()) ui.push(Beeper(ui)) else ui.open(OpenTarget("beeper-thread", mapOf("chatId" to target)))
        true
    }
}

/** Right-hand text of a list row: a pinned chat says so before its unread mark (BEEP-PRI). */
fun chatRight(c: Chat): String {
    val mark = chatMark(c)
    return if (c.pinned || c.glassesPinned) (if (mark.isEmpty()) "pin" else "pin · $mark") else mark
}

/** One chat row (name, mark, last message); opening it shows the thread. */
fun chatRow(ui: Ui, c: Chat): Row {
    val last = c.last
    return Row(
        c.name,
        right = chatRight(c),
        sub = "",
        subLines =
            layout(
                summarizeLinks(
                    (if (last?.sender == c.name) ""
                    else last?.sender?.plus(": ").orEmpty()) + last?.text.orEmpty()
                ),
                CW - 60,
                "s",
            )[0],
        open = { ui.push(BeeperThread(ui, c.id)) },
    )
}

/** The collapsed quiet group: unread chats that are muted, spam or low priority (they never count toward the badge). */
const val QUIET_ROW = "Quiet"

/**
 * Rows are rebuilt only when the chat list, paging state, order, quiet toggle or notifications change (100+ rows each
 * lay out text). PRIORITY: pins, important, unread, the rest, then one "Quiet (N)" row that Y expands. RECENT: pins,
 * then the gateway order with nothing collapsed.
 */
internal class BeeperRows(private val ui: Ui) {
    private var source: List<Chat>? = null
    private var key: List<Any?>? = null
    private var rows: List<Row> = emptyList()
    private var grouped = BeeperGroups(emptyList(), emptyList())
    var quietOpen = false
    /** The current grouping (the same one the rows were built from). */
    fun groups(): BeeperGroups { get(); return grouped }
    fun get(): List<Row> {
        val chats = ui.data.beeper.chats
        val hasMore = ui.data.beeper.hasMoreChats
        val notes = ui.data.notifications.items.filter { it.icon == "beeper" || it.source.contains("beeper", true) }
        val order = ui.beeperPrefs.prefs.order
        // A pref changed while the list is open (glassesctl pref beeper.order) also re-asks the gateway (no-op if unchanged).
        ui.data.beeper.useOrder(order)
        // Notification lists are rebuilt on every read; only the Beeper ones' identity and importance matter here.
        val now = listOf<Any?>(hasMore, order, quietOpen, notes.map { it.id to it.importance })
        if (chats === source && now == key) return rows
        source = chats; key = now
        val groups = BeeperPriority.group(chats, order, notes, ui.nowMs())
        grouped = groups
        val quiet = groups.quiet
        rows = groups.top.map { chatRow(ui, it) } +
            (if (quiet.isEmpty()) emptyList()
            else listOf(Row("$QUIET_ROW (${quiet.size})", right = if (quietOpen) "hide" else "show", open = { quietOpen = !quietOpen; key = null; ui.requestRender() },
                selLabel = if (quietOpen) "Hide quiet chats" else "Show quiet chats")) + (if (quietOpen) quiet.map { chatRow(ui, it) } else emptyList())) +
            if (hasMore) listOf(Row("Load more", right = "${chats.size} shown", open = { ui.perform { ui.data.beeper.loadMoreChats() } })) else emptyList()
        return rows
    }
    /** The chat a row stands for (null for the Quiet and Load more rows). */
    fun chatAt(index: Int): Chat? {
        val all = groups()
        val top = all.top
        if (index in top.indices) return top[index]
        val q = index - top.size - 1
        return if (all.quiet.isNotEmpty() && quietOpen && q in all.quiet.indices) all.quiet[q] else null
    }
}

/** Hold L on the Beeper list (or the Find screen): say a name, and the best matching chat opens. */
fun findTarget(ui: Ui, results: BeeperFindScreen? = null): TalkTarget =
    // "raw" asks the gateway for the speaker's exact words (no cleanup rewrite), which is what a name lookup needs.
    TalkTarget("Find chat", target = "raw", autoSend = true, send = { spoken -> findChat(ui, spoken, results) })

/** Opens the chat a spoken name clearly means; otherwise shows the candidates (or says nothing matched). */
suspend fun findChat(ui: Ui, spoken: String, results: BeeperFindScreen? = null): ActionResult {
    val query = BeeperPriority.cleanQuery(spoken)
    if (query.isBlank()) return ActionResult.failure("Say a name to find")
    val found = ui.data.beeper.find(spoken)
    // Leave the dictation preview first, so the thread or results sit directly on the list.
    if (ui.top is DictationScreen) ui.pop()
    if (found.matches.isEmpty()) {
        results?.show(query, found)
        return ActionResult.failure("No chat matches “$query”")
    }
    if (found.confident) {
        if (ui.top === results) ui.pop()
        ui.push(BeeperThread(ui, found.matches[0].chat.id))
        return ActionResult()
    }
    if (results != null && ui.top === results) results.show(query, found) else ui.push(BeeperFindScreen(ui).also { it.show(query, found) })
    return ActionResult()
}

class Beeper private constructor(ui: Ui, private val memo: BeeperRows) :
    ListView(
        ui,
        "beeper",
        items = { memo.get() },
        chrome = { t ->
            frame(
                ui,
                "Beeper",
                "beeper",
                t,
                railText = listOf("${ui.data.beeper.unreadTotal} unread"),
            )
        },
        kind = "beeper",
    ) {
    constructor(ui: Ui) : this(ui, BeeperRows(ui))

    init {
        refreshHook = { ui.data.beeper.refreshChats() }
        textContext = { "${ui.data.beeper.unreadTotal} unread" }
        target = findTarget(ui)
        act = { _ -> fix(); ui.push(BeeperListMenu(ui, memo.chatAt(cur))) }
        actLabel = "Find / pin / order"
        leg = mapOf("talkH" to "Find a chat by name")
    }

    /** Test and agent hook: whether the quiet group is expanded. */
    val quietOpen get() = memo.quietOpen

    override fun onOpen() {
        ui.data.beeper.useOrder(ui.beeperPrefs.prefs.order)
        super.onOpen()
    }
}

/** X on the Beeper list: find a chat, pin the selected one on the glasses, mark it spam, and switch the order. */
class BeeperListMenu(ui: Ui, private val chat: Chat?) :
    ListView(
        ui,
        "beeper-menu",
        items = {
            val c = chat?.let { ui.data.beeper.chat(it.id) ?: it }
            listOfNotNull(
                Row("Find chat", icon = "mic", right = "hold L", open = { ui.replaceTop(BeeperFindScreen(ui)) }),
                c?.let {
                    val pinned = it.glassesPinned
                    Row(
                        if (pinned) "Unpin from glasses" else "Pin on glasses",
                        icon = if (pinned) "cross" else "plus",
                        open = {
                            ui.pop()
                            ui.perform { ui.data.beeper.setPin(it.id, !pinned) }
                        },
                    )
                },
                c?.let { spamRow(ui, it.id) },
                ui.beeperPrefs.prefs.order.let { order ->
                    Row("Order", icon = "settings", right = order.label, open = {
                        val next = if (order == BeeperOrder.PRIORITY) BeeperOrder.RECENT else BeeperOrder.PRIORITY
                        ui.beeperPrefs.update(ui.beeperPrefs.prefs.copy(order = next))
                        ui.data.beeper.useOrder(next)
                        ui.pop()
                        ui.flash("Order: ${next.label}")
                    })
                },
            )
        },
        chrome = { t -> frame(ui, "Beeper", "beeper", t, chat?.name) },
        kind = "beeper-actions",
    ) {
    init {
        textTitle = { "Beeper actions" }
        textContext = { chat?.name.orEmpty() }
        textHint = { "Y pick B back" }
    }
}

/** What the Find screen shows: nothing searched yet, no match, or the candidates. */
class BeeperFindState(private val ui: Ui) {
    var query = ""
    var matches: List<BeeperPriority.Match> = emptyList()
    var searched = false
    fun rows(): List<Row> = when {
        !searched -> listOf(Row("Hold L and say a name", icon = "mic", selectable = false))
        matches.isEmpty() -> listOf(Row("No chat matches “$query”", selectable = false), Row("Hold L to try again", icon = "mic", selectable = false))
        else -> matches.map { chatRow(ui, ui.data.beeper.chat(it.chat.id) ?: it.chat) }
    }
}

/** Find a chat: hold L and say a name. Shows the candidates when the name is not a clear match. */
class BeeperFindScreen private constructor(ui: Ui, val state: BeeperFindState) :
    ListView(
        ui,
        "beeper-find",
        items = { state.rows() },
        chrome = { t -> frame(ui, "Find chat", "beeper", t, state.query.takeIf { it.isNotEmpty() }?.let { "“$it”" }) },
        kind = "beeper",
    ) {
    constructor(ui: Ui) : this(ui, BeeperFindState(ui))

    fun show(query: String, found: BeeperFind) {
        state.query = query
        state.matches = found.matches
        state.searched = true
        cur = 0; top = 0
        ui.requestRender()
    }

    init {
        target = findTarget(ui, this)
        leg = mapOf("talkH" to "Say a name to find")
        textTitle = { "Find chat" }
        textContext = { state.query }
        textHint = { if (state.searched && state.matches.isNotEmpty()) "Y open L again B back" else "Hold L say a name" }
    }
}

fun messageTarget(ui: Ui, chatId: String, reply: Message? = null): TalkTarget {
    val c = ui.data.beeper.chat(chatId)
    suspend fun deliver(text: String, mentions: List<Mention>): ActionResult {
        val result = if (mentions.isEmpty()) ui.data.beeper.send(chatId, text, reply?.id) else ui.data.beeper.send(chatId, text, reply?.id, mentions)
        if (result.ok) {
            val replyIndex = ui.stack.indexOfLast { it.id == "reply" }
            if (replyIndex > 0) while (ui.stack.size > replyIndex) ui.pop()
            ui.stack
                .filterIsInstance<BeeperThread>()
                .find { it.chatId == chatId }
                ?.let { it.cur = ui.data.beeper.messages(chatId).lastIndex }
        }
        return result
    }
    return TalkTarget(
        (if (reply != null) "Reply to " else "Message to ") + (c?.name ?: "chat"),
        send = { text -> deliver(text, emptyList()) },
        context = mapOf("chatId" to chatId),
        sendRich = { text, mentions -> deliver(text, mentions) },
    )
}

class BeeperThread(ui: Ui, val chatId: String) : Screen(ui, "thread-$chatId", "thread") {
    private var selectedId: String? = null
    private var initialized = false
    private var loadingOlder = false
    var cur = ui.data.beeper.messages(chatId).lastIndex
        set(value) {
            field = value
            selectedId = messages.getOrNull(value)?.id
        }

    /** A cold cache must not focus the first arrival; prepended pages retain message identity. */
    private fun syncCursor() {
        val msgs = messages
        if (msgs.isEmpty()) return
        if (!initialized && ui.data.beeper.pageState(chatId).loaded) {
            cur = msgs.lastIndex
            initialized = true
        } else if (tailId != null && selectedId == tailId && msgs.last().id != tailId) {
            cur = msgs.lastIndex // Reading the newest message: follow messages pushed into the open thread.
        } else {
            cur = selectedId?.let { id -> msgs.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                ?: cur.coerceIn(0, msgs.lastIndex)
        }
        tailId = msgs.last().id
    }
    private var tailId: String? = null

    private fun older() {
        val state = ui.data.beeper.pageState(chatId)
        if (loadingOlder || state.loadingOlder || state.nextBefore == null) return
        loadingOlder = true
        ui.flash("Loading older…")
        ui.perform {
            try { ui.data.beeper.loadOlder(chatId) }
            finally { loadingOlder = false; syncCursor() }
        }
    }
    private val chat
        get() = ui.data.beeper.chat(chatId)

    private val messages
        get() = ui.data.beeper.messages(chatId)

    override val talk
        get() = messageTarget(ui, chatId)

    override fun onOpen() {
        ui.data.beeper.threadVisible(chatId, true)
        ui.refresh { ui.data.beeper.refreshMessages(chatId) }
        ui.refresh { ui.data.beeper.markRead(chatId) }
    }

    override fun onClose() { ui.data.beeper.threadVisible(chatId, false) }

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val msgs = messages
            syncCursor()
            frame(
                ui,
                chat?.name ?: "Beeper",
                "beeper",
                t,
                chat?.network,
                listOf("${msgs.size} msgs"),
            )
            if (msgs.isEmpty()) return@with
            val bot = BOTTOM - 24
            data class Block(val m: Message, val lines: List<TextLine>, val h: Int)
            val blocks =
                msgs.map { m ->
                    val lines = clampLines(summarizeLinks(m.text), CW - 26, 4)
                    Block(
                        m,
                        lines,
                        lh("s") +
                            4 +
                            lines.size * lh() +
                            (if (m.reactions.isNotEmpty()) es() + 4 else 0) +
                            10 +
                            (if (m.replyTo != null) lh("s") else 0),
                    )
                }
            var start = 0
            var acc = 0
            val avail = bot - TOP
            for (i in cur downTo 0) {
                acc += blocks[i].h
                if (acc > avail) {
                    start = i + 1
                    break
                }
            }
            if (start > cur) start = cur
            save()
            clipRect(0, TOP - 2, W, bot - TOP + 2)
            var y = TOP
            for (i in start..blocks.lastIndex) {
                val b = blocks[i]
                if (y > bot) break
                if (i == cur) p.fr(CX - 8, y, 5, b.h - 8)
                tx(caseText(b.m.sender), CX + 4, y, "s")
                y += lh("s") + 4
                b.m.replyTo?.let {
                    tx(fit("re: " + it.text, CW - 30, "s"), CX + 4, y - 2, "s", tone = SECONDARY)
                    y += lh("s")
                }
                b.lines.forEach {
                    drawLine(it, CX + 4, y)
                    y += lh()
                }
                if (b.m.reactions.isNotEmpty()) {
                    var x = CX + 4
                    val rx = b.m.reactions.filterNot { it.mine } + b.m.reactions.filter { it.mine }
                    rx.forEach {
                        if (it.mine) p.rr(x - 3, y - 1, es() + 6, es() + 4, 4, 1.5)
                        emo(it.key, x, y + 1, es())
                        x += es() + 10
                    }
                    y += es() + 4
                }
                y += 10
            }
            restore()
            p.fr(CX, BOTTOM - 22, CW, 1, tone = TERTIARY)
            hintText("Y read · X actions · L talk", CX, BOTTOM - 16, "s")
        }

    override fun key(k: String): Boolean {
        val m = messages
        if (m.isEmpty()) return false
        syncCursor()
        when (k) {
            "up" -> if (cur == 0) older() else cur--
            "down" -> cur = min(m.lastIndex, cur + 1)
            "sel" -> ui.push(MessageReader(ui, chatId, m[cur].id))
            "act" -> ui.push(MessageActions(ui, chatId, m[cur].id))
            else -> return false
        }
        return true
    }

    override fun legend() =
        mapOf(
            "up" to "Older message",
            "down" to "Newer message",
            "sel" to "Read full message",
            "act" to "React / reply",
            "talkH" to "New message to ${chat?.name}",
        )

    override fun drawText(tg: TextGrid) {
        val msgs = messages
        syncCursor()
        TextChrome.header(tg, chat?.name ?: "Beeper", chat?.network.orEmpty())
        if (msgs.isEmpty()) tg.put(0, 2, "No messages")
        else {
            val m = msgs[cur]
            val body = (m.replyTo?.let { "re: ${it.sender}: ${it.text}\n" } ?: "") + summarizeLinks(m.text)
            val label = (if (m.sender == chat?.name) "" else m.sender + ": ") + body
            val rx = m.reactions.joinToString(" ") {
                (if (it.mine) "You: " else "Rx: ") + "[${emojiName(it.key)}]" + (if (it.count > 1) "x${it.count}" else "")
            }
            val selectedLines = gridLines(label, tg.cols - 1).take(3)
            val reactionLines = if (rx.isEmpty()) emptyList() else gridLines(rx, tg.cols - 1).take(2)
            val previousRows = (tg.rows - 2 - selectedLines.size - reactionLines.size).coerceAtLeast(0)
            var row = 1
            msgs.take(cur).takeLast(previousRows).forEach { previous ->
                tg.put(0, row++, TextChrome.fit(" " + (if (previous.sender == chat?.name) "" else previous.sender + ": ") + summarizeLinks(previous.text), tg.cols))
            }
            selectedLines.forEachIndexed { index, line ->
                if (row < tg.rows - 1) tg.put(0, row++, (if (index == 0) ">" else " ") + line)
            }
            reactionLines.forEach { if (row < tg.rows - 1) tg.put(0, row++, " " + it) }
        }
        TextChrome.footer(tg, hint = "Y read X react L say")
    }
}

private fun message(ui: Ui, chatId: String, id: String) =
    ui.data.beeper.messages(chatId).find { it.id == id }

class MessageActions(ui: Ui, chatId: String, id: String) :
    ListView(
        ui,
        "msgact",
        items = {
            listOf(
                Row(
                    "React",
                    emo = "😂",
                    open = {
                        ui.pop()
                        ui.push(ReactionPicker(ui, chatId, id))
                    },
                ),
                Row(
                    "Reply",
                    icon = "beeper",
                    open = {
                        ui.pop()
                        ui.push(ReplyScreen(ui, chatId, id))
                    },
                ),
                Row(
                    "Read full message",
                    icon = "docs",
                    open = {
                        ui.pop()
                        ui.push(MessageReader(ui, chatId, id))
                    },
                ),
                spamRow(ui, chatId),
            )
        },
        chrome = { t -> frame(ui, "Message", "beeper", t, message(ui, chatId, id)?.sender) },
        kind = "beeper-actions",
    )

/** Per-chat spam override. Spam chats stay listed and readable; they only stop notifying and counting. */
fun spamRow(ui: Ui, chatId: String): Row {
    val spam = ui.data.beeper.chat(chatId)?.spam == true
    return Row(
        if (spam) "Not spam" else "Mark spam",
        icon = if (spam) "check" else "warn",
        open = {
            ui.pop()
            ui.perform { ui.data.beeper.setSpam(chatId, !spam) }
        },
    )
}

class ReplyScreen(ui: Ui, private val chatId: String, private val messageId: String) :
    ReaderView(
        ui,
        "reply",
        text = { "“" + summarizeLinks(message(ui, chatId, messageId)?.text.orEmpty()) + "”" },
        icon = "beeper",
        rows = (BOTTOM - TOP - 60) / lh(),
    ) {
    override val talk
        get() = messageTarget(ui, chatId, message(ui, chatId, messageId))

    override fun header(g: Gfx, t: Double) {
        g.frame(
            ui,
            "Reply to " + message(ui, chatId, messageId)?.sender,
            "beeper",
            t,
            ui.data.beeper.chat(chatId)?.network,
        )
    }

    override val textTitle get() = "Reply to " + message(ui, chatId, messageId)?.sender.orEmpty()
    override val textContext get() = ui.data.beeper.chat(chatId)?.network.orEmpty()
    override val textHint get() = "L reply B back"

    override fun footer(g: Gfx, n: Int) {
        with(g) {
            p.fr(CX, BOTTOM - 40, CW, 2, tone = TERTIARY)
            hintText("Hold L to reply", CX, BOTTOM - 30)
            if (n > 1) txR("◀▶ ${page+1}/$n", W - 14, BOTTOM - 60, "s", tone = SECONDARY)
        }
    }

    override fun legend() =
        super.legend() +
            mapOf("talkH" to "Dictate reply to ${message(ui,chatId,messageId)?.sender}")
}

class MessageReader(ui: Ui, private val chatId: String, private val messageId: String) :
    ReaderView(
        ui,
        "msg",
        text = {
            message(ui, chatId, messageId)
                ?.let { m ->
                    summarizeLinks(m.text) +
                        (if (m.reactions.any { !it.mine })
                            "\n\nReactions: " +
                                m.reactions.filterNot { it.mine }.joinToString(" ") { it.key }
                        else "") +
                        (m.reactions.find { it.mine }?.let { "\nYou: " + it.key } ?: "")
                }
                .orEmpty()
        },
        icon = "beeper",
        y0off = lh("s") + 6,
        rows = (BOTTOM - TOP - lh("s") - 6 - 26) / lh(),
    ) {
    override val talk
        get() = messageTarget(ui, chatId, message(ui, chatId, messageId))

    override fun header(g: Gfx, t: Double) {
        val c = ui.data.beeper.chat(chatId)
        with(g) {
            frame(ui, message(ui, chatId, messageId)?.sender ?: "Message", "beeper", t, c?.network)
            tx(
                caseText(c?.name.orEmpty() + " · " + c?.network.orEmpty()),
                CX,
                TOP - 2,
                "s",
                tone = SECONDARY,
            )
        }
    }

    override val textTitle get() = message(ui, chatId, messageId)?.sender ?: "Message"
    override val textContext get() = ui.data.beeper.chat(chatId)?.network.orEmpty()
    override val textHint get() = "X actions L reply B back"
    override fun textContent(): String = message(ui, chatId, messageId)?.let { m ->
        listOf(m.at.takeIf { it.isNotBlank() }?.let { "At: $it" }.orEmpty(),
            m.replyTo?.let { "Reply to ${it.sender}: ${summarizeLinks(it.text)}" }.orEmpty(),
            summarizeLinks(m.text),
            m.reactions.takeIf { it.isNotEmpty() }?.joinToString("\n") {
                (if (it.mine) "You: " else "Reaction: ") + gridText(it.key)
            }.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
    }.orEmpty()

    override fun footer(g: Gfx, n: Int) {
        g.pageFooter(page, n, W - 62, BOTTOM - 40)
        with(g) {
            p.fr(CX, BOTTOM - 22, CW, 1, tone = TERTIARY)
            hintText("X react / reply · L reply", CX, BOTTOM - 16, "s")
        }
    }

    override fun key(k: String): Boolean {
        if (k == "act") {
            ui.push(MessageActions(ui, chatId, messageId))
            return true
        }
        return super.key(k)
    }

    override fun legend() =
        super.legend() + mapOf("act" to "React / reply", "talkH" to "Reply to this message")
}

class ReactionPicker(ui: Ui, private val chatId: String, private val messageId: String) :
    Screen(ui, "react") {
    private val existing =
        message(ui, chatId, messageId)
            ?.reactions
            .orEmpty()
            .sortedByDescending { it.mine }
            .map { it.key }
            .distinct()
    private val own get() = message(ui, chatId, messageId)?.reactions?.filter { it.mine }?.map { it.key }?.toSet().orEmpty()
    private val palette = existing + EMO_KEYS.filterNot { it in existing }
    var i = 0

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val m = message(ui, chatId, messageId)
            frame(ui, "React", "beeper", t, m?.sender)
            var y = TOP
            clampLines("“" + summarizeLinks(m?.text.orEmpty()) + "”", CW - 12, 2).forEach {
                drawLine(it, CX, y)
                y += lh()
            }
            val vis = 5
            val cell = 64
            val x0 = CX + (CW - vis * cell) / 2.0
            val yy = TOP + 2 * lh() + 8
            val start = (i - 1).coerceIn(0, max(0, palette.size - vis))
            for (j in 0 until min(vis, palette.size - start)) {
                val k = start + j
                val x = x0 + j * cell
                withTone(if (k == i) PRIMARY else TERTIARY) {
                    if (k == i) p.rr(x + 2, yy - 6, cell - 4, cell + 4, 8, 3)
                    emo(palette[k], x + 10, yy + 2, 44, tone = if (k == i) PRIMARY else TERTIARY)
                    if (palette[k] in own) p.rr(x + 6, yy - 2, cell - 12, cell - 4, 6, 1.5)
                    if (k < existing.size)
                        p.poly(
                            points(
                                x + cell / 2 - 7,
                                yy + cell + 14,
                                x + cell / 2 + 7,
                                yy + cell + 14,
                                x + cell / 2,
                                yy + cell + 6,
                            )
                        )
                }
            }
            if (start > 0) p.poly(points(x0 - 14, yy + 24, x0 - 4, yy + 16, x0 - 4, yy + 32))
            if (start + vis < palette.size)
                p.poly(
                    points(
                        x0 + vis * cell + 14,
                        yy + 24,
                        x0 + vis * cell + 4,
                        yy + 16,
                        x0 + vis * cell + 4,
                        yy + 32,
                    )
                )
            if (existing.isNotEmpty() && start < existing.size)
                hintText(if (palette[i] in own) "Your reaction · Y remove" else "already on message", CX, BOTTOM - 34, "s")
            txR("${i+1}/${palette.size}", W - 14, BOTTOM - 16, "s", tone = SECONDARY)
        }

    override fun key(k: String): Boolean {
        when (k) {
            "left",
            "up" -> i = max(0, i - 1)
            "right",
            "down" -> i = min(palette.lastIndex, i + 1)
            "sel" ->
                ui.perform(after = { ui.pop() }) {
                    ui.data.beeper.react(chatId, messageId, palette[i], remove = palette[i] in own)
                }
            else -> return false
        }
        return true
    }

    override fun legend() =
        mapOf(
            "left" to "Prev reaction",
            "right" to "Next reaction",
            "up" to "Prev reaction",
            "down" to "Next reaction",
            "sel" to if (palette[i] in own) "Remove reaction" else "Send reaction",
        )

    override fun drawText(tg: TextGrid) {
        TextChrome.header(tg, "React", message(ui, chatId, messageId)?.sender.orEmpty())
        val name = emojiName(palette[i])
        gridLines(name, tg.cols).forEachIndexed { j, line -> tg.put(0, j + 2, line) }
        if (palette[i] in existing) tg.put(0, tg.rows - 2, if (palette[i] in own) "Your reaction" else "Already on message")
        TextChrome.footer(tg, i, palette.size, if (palette[i] in own) "Y remove <> pick" else "Y send <> pick")
    }
}
