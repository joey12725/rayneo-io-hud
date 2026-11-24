package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.data.*
import io.lampyr.glasses.data.Thread
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import kotlin.math.*

private fun statusBadge(status: String) = when (status) {
    "input", "request" -> "ASK"; "approval" -> "OK?"; "working", "waiting" -> "RUN"
    "done" -> "DONE"; "failed" -> "FAIL"; "limited" -> "LIMIT"; else -> status.uppercase()
}

private val NEED = T3_NEEDS_YOU

private fun detail(ui: Ui, key: ThreadKey) = ui.data.t3.detail(key.machine, key.id)

private fun workSecs(ui: Ui, step: Step) = step.elapsedSec + max(0.0, ui.nowS() - step.since)

fun Gfx.t3Frame(th: Thread, page: Int = 0, n: Int = 1) {
    val ic =
        when (th.status) {
            "done" -> "check"
            "working" -> "spin"
            in NEED -> "warn"
            else -> "archive"
        }
    bottomRow(th.status.uppercase(), th.provider, page, n, ic)
}

class T3Root(ui: Ui) :
    ListView(
        ui,
        "t3",
        items = {
            val threads = ui.data.t3.threads
            fun row(th: Thread): Row {
                val step = th.step
                val sub =
                    if (th.status in listOf("working", "waiting") && step != null)
                        "${step.label}: ${step.what} · ${fmtS(workSecs(ui,step))}"
                    else "${th.project} · ${th.provider}"
                return Row(
                    th.title,
                    tag = th.status.uppercase(),
                    sub = sub,
                    subLines = layout(sub, CW - 60, "s")[0],
                    bitmapSubFallback = th.project.takeUnless { th.status in listOf("working", "waiting") && step != null },
                    right = age(th.ageSec),
                    open = { ui.push(T3Thread(ui, th.key)) },
                )
            }
            listOf(Row("NEEDS YOU", sep = true)) +
                threads.filter { it.status in NEED }.map(::row) +
                Row("DONE", sep = true) +
                threads.filter { it.status == "done" }.map(::row) +
                Row("WORKING", sep = true) +
                threads.filter { it.status in listOf("working", "waiting") }.map(::row) +
                listOf(
                    Row("MORE", sep = true),
                    Row("New thread", icon = "plus", open = { ui.push(NewThread(ui)) }),
                    Row(
                        "Usage limits",
                        icon = "gauge",
                        right =
                            ui.data.t3.usage
                                .firstOrNull()
                                ?.windows
                                ?.firstOrNull()
                                ?.let { "${it.usedPercent}% · " + resetLabel(it.resetsAt, ui) }
                                .orEmpty(),
                        open = { ui.push(T3Usage(ui)) },
                    ),
                    Row(
                        "Environments",
                        icon = "env",
                        // Same rows as the rail's offline count (polled with the thread list), never 0/0.
                        right = ui.data.t3.machines.let { m -> if (m.isEmpty()) "" else "${m.count { it.online }}/${m.size} online" },
                        open = { ui.push(T3Environments(ui)) },
                    ),
                    Row(
                        "Settled",
                        icon = "archive",
                        right = "25 recent",
                        open = { ui.push(T3Settled(ui)) },
                    ),
                    // Gateway setting: needs-you pop-ups from every thread, not only watched ones (finished stays watched-only).
                    Row(
                        "Notify all needing me",
                        icon = "bell",
                        right = if (ui.data.t3.notifyAllNeeds) "On" else "Off",
                        open = { ui.perform { ui.data.t3.setNotifyAllNeeds(!ui.data.t3.notifyAllNeeds) } },
                    ),
                )
        },
        chrome = { t ->
            frame(
                ui,
                "T3 Code",
                "t3",
                t,
                railText =
                    listOf(
                        "${ui.data.t3.needYouCount} need you"
                    ) + if (ui.data.t3.offlineCount > 0) listOf("${ui.data.t3.offlineCount} offline") else emptyList(),
            )
        },
    ) {
    init {
        refreshHook = { ui.data.t3.refreshThreads() }
        textContext = { if (ui.data.t3.offlineCount > 0) "${ui.data.t3.offlineCount} offline" else "${ui.data.t3.needYouCount} need you" }
        textItems = { rows().map { it.copy(
            label = if (it.sep && it.label == "NEEDS YOU") "NEEDS YOU (${ui.data.t3.needYouCount})" else it.label,
            tag = it.tag?.let { tag -> statusBadge(tag.lowercase()) }) } }
    }
}

/** Routes fresh cached thread state without holding a mutable backend object in a screen. */
class T3Thread(ui: Ui, val threadKey: ThreadKey) :
    Screen(ui, "t3thread-${threadKey.machine}-${threadKey.id}", "t3thread") {
    private var signature = ""
    private var delegate: View? = null

    private fun current(): View {
        val d = detail(ui, threadKey)
        val sig = "${d?.thread?.status}:${d?.request}:${d?.latest?.text}"
        if (delegate == null || signature != sig) {
            signature = sig
            delegate =
                when {
                    d == null ->
                        ReaderView(
                            ui,
                            "t3loading",
                            { "Loading thread…" },
                            "T3 Code",
                            fullWidth = true,
                        )
                    d.thread.status in listOf("working", "waiting") -> T3Working(ui, threadKey)
                    d.request?.kind == "approval" -> T3Approval(ui, threadKey)
                    d.thread.status == "request" || d.request?.kind in listOf("request", "guest", "message") ->
                        T3Reader(ui, threadKey, d.request?.messages?.takeIf { it.isNotEmpty() }
                            ?.joinToString("\n\n") { it.text } ?: "Guest message unavailable")
                    d.request != null -> T3Question(ui, threadKey)
                    else -> T3Reader(ui, threadKey)
                }
        }
        return delegate!!
    }

    override fun onOpen() {
        ui.refresh { ui.data.t3.refreshThread(threadKey.machine, threadKey.id) }
        ui.refresh { ui.data.t3.visit(threadKey.machine, threadKey.id) }
    }

    override fun draw(g: Gfx, t: Double) = current().draw(g, t)

    override fun drawText(tg: TextGrid) = current().drawText(tg)

    override fun key(k: String) = current().key(k)

    override fun legend() = current().legend()

    override val talk
        get() = current().talk

    override val needsAnimation
        get() = current().needsAnimation
}

fun t3Talk(ui: Ui, key: ThreadKey): TalkTarget {
    val d = detail(ui, key)
    val working = d?.thread?.status in listOf("working", "waiting")
    suspend fun send(text: String, mode: String): ActionResult {
        val result = ui.data.t3.send(key.machine, key.id, text, mode)
        if (result.ok && !working) {
            val i = ui.stack.indexOfFirst { it is T3Thread && it.threadKey == key }
            if (i > 0) {
                while (ui.stack.lastIndex > i) ui.pop()
            } else ui.replaceTop(T3Thread(ui, key))
        }
        return result
    }
    return TalkTarget(
        (if (working) "Steer or queue: " else "Message: ") + d?.thread?.title,
        "t3",
        send = { send(it, if (working) "queue" else "send") },
        steer = if (working) ({ text -> send(text, "steer") }) else null,
        context = mapOf("machine" to key.machine, "thread" to key.id),
    )
}

class T3Reader(ui: Ui, private val threadKey: ThreadKey, private val overrideText: String? = null) :
    ReaderView(
        ui,
        "t3d",
        { overrideText ?: detail(ui, threadKey)?.latest?.text.orEmpty() },
        fullWidth = true,
        rows = (BOTTOM - TOP - 26) / lh(),
    ) {
    override val talk
        get() = t3Talk(ui, threadKey)

    override val textTitle get() = detail(ui, threadKey)?.thread?.title ?: "T3 Code"
    override val textContext get() = detail(ui, threadKey)?.thread?.let { statusBadge(it.status) }.orEmpty()
    override val textHint get() = "^v page X log L say"
    override val textFooterState get() = detail(ui, threadKey)?.thread?.let { statusBadge(it.status) }.orEmpty()

    override fun header(g: Gfx, t: Double) {
        detail(ui, threadKey)?.thread?.let { g.frameW(ui, it.title, it.project) }
    }

    override fun footer(g: Gfx, n: Int) {
        detail(ui, threadKey)?.thread?.let { g.t3Frame(it, page, n) }
    }

    override fun key(k: String): Boolean {
        if (k == "act") {
            ui.push(T3ThreadActions(ui, threadKey))
            return true
        }
        return super.key(k)
    }

    override fun legend() =
        mapOf(
            "up" to "Prev page",
            "down" to "Next page",
            "act" to "Thread menu (history, watch)",
            "talkH" to "Message the thread",
        )
}

class T3Working(ui: Ui, private val threadKey: ThreadKey) : Screen(ui, "t3w") {
    override val talk
        get() = t3Talk(ui, threadKey)

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val d = detail(ui, threadKey) ?: return@with
            val th = d.thread
            frameW(ui, th.title, th.project)
            val step = d.working?.now ?: th.step ?: return@with
            val s = workSecs(ui, step)
            var y = TOP - 2
            tx("NOW · " + step.label.uppercase(), X0, y, "s", tone = SECONDARY)
            y += lh("s") + 2
            clampLines(step.what, XW, 2).forEach {
                drawLine(it, X0, y)
                y += lh()
            }
            tx(fmtS(s), X0, y + 2, "l")
            val width = tw(fmtS(s), "l")
            if (step.quietSec != null)
                tx("no output for " + fmtS(step.quietSec + max(0.0, ui.nowS() - step.since)), X0 + width + 16, y + 8, "s", tone = SECONDARY)
            else if (s > 1800) tx("long-running", X0 + width + 16, y + 8, "s", tone = SECONDARY)
            y += lh("l") + 6
            p.fr(X0, y, XW, 1, tone = TERTIARY)
            y += 6
            tx("RECENT", X0, y, "s", tone = SECONDARY)
            y += lh("s") + 2
            for (recent in d.working?.recent.orEmpty().take(4)) {
                if (y > BOTTOM - 24 - lh()) break
                icon("check", X0, y, 18)
                tx(fit(recent.label, XW - 80), X0 + 26, y)
                txR(fmtS(recent.durSec), W - 14, y, "s", tone = SECONDARY)
                y += lh() + 2
            }
            t3Frame(th)
        }

    override fun key(k: String): Boolean {
        when (k) {
            "sel" -> ui.push(T3Reader(ui, threadKey, detail(ui, threadKey)?.latest?.text))
            "act" -> ui.push(T3ThreadActions(ui, threadKey))
            else -> return false
        }
        return true
    }

    override fun legend() =
        mapOf(
            "sel" to "Latest agent message",
            "act" to "Thread menu (history, watch)",
            "talkH" to "Message the thread (steer)",
        )

    override fun drawText(tg: TextGrid) {
        val d = detail(ui, threadKey)
        TextChrome.header(tg, d?.thread?.title ?: "T3 Code", "RUN")
        val step = d?.working?.now ?: d?.thread?.step
        if (step == null) { tg.put(0, 2, "Waiting for activity"); TextChrome.footer(tg, hint = "Y read X history L say"); return }
        tg.put(0, 1, TextChrome.fit("-NOW- ${step.label}", tg.cols))
        val whatRows = if (tg.rows >= 7) 2 else 1
        val ls = gridLines(step.what, tg.cols)
        ls.take(whatRows).forEachIndexed { i, line ->
            tg.put(0, i + 2, if (i == whatRows - 1 && ls.size > whatRows) TextChrome.fit(line + "...", tg.cols) else line)
        }
        val secs = workSecs(ui, step)
        val warning = step.quietSec?.let { "No output " + fmtS(it + max(0.0, ui.nowS() - step.since)) }
            ?: if (secs > 1800) "long-running" else ""
        tg.put(0, 2 + whatRows, TextChrome.fit("Elapsed " + fmtS(secs), tg.cols))
        if (warning.isNotBlank() && tg.rows >= 7) tg.put(0, 3 + whatRows, warning)
        d?.working?.recent.orEmpty().take(if (warning.isNotBlank()) 0 else (tg.rows - 4 - whatRows).coerceAtLeast(0)).forEachIndexed { i, recent ->
            TextChrome.pair(tg, 3 + whatRows + i, " " + recent.label, fmtS(recent.durSec))
        }
        TextChrome.footer(tg, hint = "Y read X history L say")
    }
}

class T3History(ui: Ui, key: ThreadKey) :
    ListView(
        ui,
        "t3hist",
        x0 = X0,
        w0 = XW,
        y0 = TOP,
        items = {
            val d = detail(ui, key)
            d?.history
                .orEmpty()
                .filter { it.who in listOf("user", "agent") }
                .map { e ->
                    val who = if (e.who == "user") "You" else "Agent"
                    Row(
                        e.text.substringBefore('\n'),
                        tag = who.uppercase(),
                        open = {
                            ui.push(
                                object :
                                    ReaderView(
                                        ui,
                                        "t3e",
                                        { e.text },
                                        fullWidth = true,
                                        rows = (BOTTOM - TOP - 26) / lh(),
                                    ) {
                                    override val textTitle get() = "Thread entry"
                                    override fun textContent() = d?.thread?.title.orEmpty() + "\n" + e.text
                                    override val textContext get() = who
                                    override fun header(g: Gfx, t: Double) {
                                        g.frameW(
                                            ui,
                                            who + " · " + d?.thread?.title,
                                            d?.thread?.project,
                                        )
                                    }

                                    override fun footer(g: Gfx, n: Int) {
                                        g.bottomRow(
                                            who.uppercase(),
                                            d?.thread?.provider.orEmpty(),
                                            page,
                                            n,
                                        )
                                    }
                                }
                            )
                        },
                        selLabel = "Read entry",
                    )
                }
        },
        chrome = { _ ->
            val th = detail(ui, key)?.thread
            frameW(ui, "History · " + th?.title, th?.project)
        },
    ) {
    init {
        cur = rows().lastIndex.coerceAtLeast(0)
        animateChrome = false
        leg = mapOf("up" to "Older", "down" to "Newer")
    }
}

class T3Approval(ui: Ui, private val key: ThreadKey) :
    ListView(
        ui,
        "t3a",
        x0 = X0,
        w0 = XW,
        y0 = TOP + 78,
        reserveBottom = true,
        items = {
            val request = detail(ui, key)?.request
            listOf("Approve once", "Approve for this thread", "Deny").mapIndexed { i, label ->
                Row(
                    label,
                    icon = if (i == 2) "cross" else "check",
                    open = {
                        ui.perform {
                            ui.data.t3.respond(
                                key.machine,
                                key.id,
                                request?.id.orEmpty(),
                                listOf("accept", "acceptForSession", "decline")[i],
                            )
                        }
                    },
                )
            }
        },
    ) {
    init {
        leg = mapOf("sel" to "Choose", "act" to "Thread menu", "left" to "Read command", "right" to "Read command")
        animateChrome = false
        act = { ui.push(T3ThreadActions(ui, key)) }
    }

    var approvalPage = 0
    private var reviewedRequest: String? = null
    private var readLastPage = false
    var bitmapPage = 0
        private set
    private var readLastBitmapPage = false

    private fun commandPages() = pages(detail(ui, key)?.request?.approval?.command.orEmpty(), XW - 20, 2)
    private fun reasonPages() = layout(detail(ui, key)?.request?.approval?.reason.orEmpty(), XW - 112, "s")
    private fun bitmapPageCount() = maxOf(commandPages().size, reasonPages().size)

    override fun draw(g: Gfx, t: Double) {
        syncReview()
        val d = detail(ui, key)
        val a = d?.request?.approval
        val commands = commandPages()
        val reasons = reasonPages()
        val count = bitmapPageCount()
        bitmapPage = bitmapPage.coerceIn(0, count - 1)
        if (bitmapPage == count - 1) readLastBitmapPage = true
        with(g) {
            frameW(ui, d?.thread?.title.orEmpty(), a?.kind?.uppercase().orEmpty())
            p.rr(X0, TOP, XW, 48, 4, 2)
            commands[bitmapPage.coerceAtMost(commands.lastIndex)].forEachIndexed { i, line ->
                drawLine(line, X0 + 10, TOP + 6 + i * lh())
            }
            reasons.getOrNull(bitmapPage)?.let { drawLine(it, X0, TOP + 52, "s") }
            if (count > 1) txR("◀▶ ${bitmapPage + 1}/$count", W - 12, TOP + 52, "s", tone = SECONDARY)
            d?.thread?.let {
                if (readLastBitmapPage) t3Frame(it)
                else bottomRow("READ ALL", it.provider, leftIcon = "warn")
            }
        }
        super.draw(g, t)
    }
    private fun approvalText(): String {
        val a = detail(ui, key)?.request?.approval ?: return "Approval expired"
        return a.kind + "\n" + a.command + "\n" + a.reason
    }
    private fun syncReview(): Boolean {
        val revision = detail(ui, key)?.request?.id.orEmpty() + "\n" + approvalText()
        if (reviewedRequest != revision) {
            if (reviewedRequest != null) approvalPage = 0
            reviewedRequest = revision
            readLastPage = false
            bitmapPage = 0
            readLastBitmapPage = false
            if (ui.isText) cur = 2 // Deny is safe before the command has been read.
            return true
        }
        return false
    }
    private fun approvalPages() = ui.textPages(approvalText(), (ui.textDisplay.rows - 4).coerceAtLeast(1))
    override fun key(k: String): Boolean {
        if (!ui.isText) {
            syncReview()
            if (k in listOf("left", "right")) {
                bitmapPage = (bitmapPage + if (k == "right") 1 else -1).coerceIn(0, bitmapPageCount() - 1)
                return true
            }
            if (!readLastBitmapPage && k == "sel" && cur < 2) {
                ui.flash("Read all pages first")
                return true
            }
        }
        if (ui.isText) {
            if (syncReview() && k == "sel") return true
            if (!readLastPage && k == "sel" && cur < 2) {
                ui.flash("Read all pages first")
                return true
            }
            if (!readLastPage && k in listOf("up", "down")) { cur = 2; return true }
        }
        if (ui.isText && k in listOf("left", "right")) {
            approvalPage = (approvalPage + if (k == "right") 1 else -1).coerceIn(0, approvalPages().lastIndex)
            return true
        }
        return super.key(k)
    }
    override fun drawText(tg: TextGrid) {
        syncReview()
        val ps = gridPages(approvalText(), tg.cols, (tg.rows - 4).coerceAtLeast(1))
        approvalPage = approvalPage.coerceIn(0, ps.lastIndex)
        if (approvalPage == ps.lastIndex) readLastPage = true
        TextChrome.header(tg, detail(ui, key)?.thread?.title ?: "T3 Code", "OK?")
        ps[approvalPage].forEachIndexed { i, line -> tg.put(0, i + 1, line) }
        val start = maxOf(2, tg.rows - 3)
        TextChrome.list(tg, rows(), cur, start, tg.rows - 1)
        TextChrome.footer(tg, approvalPage, ps.size, if (readLastPage) "<> read Y pick B back" else "<> read all B back")
    }
}

class T3Question(ui: Ui, private val threadKey: ThreadKey) : Screen(ui, "t3q") {
    override val needsAnimation
        get() = false

    var qp = 0
    var questionIndex = 0
    private val selected = mutableMapOf<String, MutableSet<Int>>()
    private val answers = linkedMapOf<String, List<String>>()
    private val d
        get() = detail(ui, threadKey)

    private val question
        get() =
            d?.request?.let {
                it.questions.getOrNull(questionIndex)
                    ?: if (it.kind == "text")
                        Question(it.id, question = "Hold L and say your answer.")
                    else null
            }

    private val list =
        ListView(ui, "t3qrows", x0 = X0, w0 = XW, y0 = TOP + 2 * lh() + 12, reserveBottom = true, items = { rows() })

    private fun selections(q: Question) = selected.getOrPut(q.id) { q.selected.toMutableSet() }

    private suspend fun submitAnswer(values: List<String>): ActionResult {
        val q = question ?: return ActionResult.failure("Request expired")
        answers[q.id] = values
        val req = d?.request ?: return ActionResult.failure("Request expired")
        if (questionIndex < req.questions.lastIndex) {
            questionIndex++
            qp = 0
            list.cur = 0
            ui.requestRender()
            return ActionResult()
        }
        return ui.data.t3.respond(
            threadKey.machine,
            threadKey.id,
            req.id,
            answers = answers.toMap(),
        )
    }

    private fun answer(values: List<String>) {
        ui.perform { submitAnswer(values) }
    }

    private fun rows(): List<Row> {
        val q = question ?: return listOf(Row("Hold L to answer", icon = "mic"))
        return q.options.mapIndexed { i, o ->
            Row(
                (if (q.multi) if (i in selections(q)) "[x] " else "[ ] " else "") + o.label,
                sub = o.description.takeIf { ui.isText && it.isNotBlank() },
                selLabel = if (q.multi) "Toggle" else "Choose",
                open = {
                    if (q.multi) {
                        val set = selections(q)
                        if (!set.add(i)) set.remove(i)
                    } else answer(listOf(o.label))
                },
            )
        } +
            (if (q.multi)
                listOf(
                    Row(
                        "Submit ${selections(q).size} selected",
                        icon = "check",
                        open = { answer(selections(q).sorted().map { q.options[it].label }) },
                    )
                )
            else emptyList()) +
            Row("Other… (hold L)", icon = "mic", open = { ui.flash("Hold L to dictate") })
    }

    override val talk
        get() =
            TalkTarget(
                "Other answer",
                "other",
                send = { text ->
                    val q = question
                    val picked = if (q?.multi == true) selections(q).sorted().map { q.options[it].label } else emptyList()
                    submitAnswer(picked + text)
                },
                context = mapOf("machine" to threadKey.machine, "thread" to threadKey.id),
            )

    override fun draw(g: Gfx, t: Double) {
        with(g) {
            val th = d?.thread
            frameW(ui, th?.title.orEmpty(), th?.project)
            val ps = pages(question?.question.orEmpty(), XW - 70, 2)
            qp = qp.coerceIn(0, ps.lastIndex)
            var y = TOP
            ps[qp].forEach {
                drawLine(it, X0, y)
                y += lh()
            }
            if (ps.size > 1) txR("◀▶ ${qp+1}/${ps.size}", W - 12, TOP, "s", tone = SECONDARY)
            p.fr(X0, TOP + 2 * lh() + 4, XW, 1, tone = TERTIARY)
            th?.let { t3Frame(it) }
        }
        list.draw(g, t)
    }

    private fun questionBodyRows(rows: Int) = max(1, rows - if ((question?.options?.size ?: 0) > 2) 5 else 4)
    private fun questionPages() = ui.textPages(question?.question.orEmpty(), questionBodyRows(ui.textDisplay.rows))

    override fun key(k: String): Boolean {
        if (ui.isText && k in listOf("left", "right")) {
            qp = (qp + if (k == "right") 1 else -1).coerceIn(0, questionPages().lastIndex)
            return true
        }
        when (k) {
            "left" -> {
                qp--
                return true
            }
            "right" -> {
                qp++
                return true
            }
            "act" -> {
                ui.push(T3ThreadActions(ui, threadKey))
                return true
            }
        }
        return list.key(k)
    }

    override fun legend() =
        list.legend() +
            mapOf("left" to "Question page", "right" to "Question page", "act" to "Thread menu")

    override fun drawText(tg: TextGrid) {
        val bodyRows = questionBodyRows(tg.rows)
        val ps = gridPages(question?.question.orEmpty(), tg.cols, bodyRows)
        qp = qp.coerceIn(0, ps.lastIndex)
        TextChrome.header(tg, if (question?.multi == true) "T3 multi-select" else "T3 question",
            "Q${questionIndex + 1}/${max(1, d?.request?.questions?.size ?: 1)}")
        ps[qp].forEachIndexed { i, line -> tg.put(0, i + 1, line) }
        TextChrome.list(tg, rows(), list.cur, 1 + bodyRows, wrapFocus = true)
        val hint = if (question?.multi == true) when (list.cur) {
            question?.options?.size -> "Y submit ^v pick"
            rows().lastIndex -> "L say ^v pick"
            else -> "Y toggle v submit"
        } else if (tg.cols >= 26) "<> text Y pick L say" else "<> text Y pick"
        TextChrome.footer(tg, qp, ps.size, hint)
    }
}

fun resetLabel(value: String, ui: Ui): String =
    try {
        val secs = (java.time.Instant.parse(value).toEpochMilli() - ui.nowMs()) / 1000
        val h = secs / 3600
        val m = secs % 3600 / 60
        if (h < 24) "${h}h ${m}m" else displayTime(value, ui)
    } catch (_: Exception) {
        value
    }

class T3Usage(ui: Ui) : Screen(ui, "usage") {
    var page = 0
    private var windowPage = 0
    private var textPage = 0
    private fun usageText(): String {
        val u = ui.data.t3.usage.getOrNull(page) ?: return "No usage data"
        return u.windows.joinToString("\n") { w ->
            w.label + "\n" + TextChrome.bar(w.usedPercent / 100.0) + " ${w.usedPercent}%\n" +
                "Reset " + resetLabel(w.resetsAt, ui) + " . " + w.pace + " pace"
        } + if (u.credits.isEmpty()) "" else "\nBanked: " + u.credits.joinToString(", ") { "${it.label} x${it.availableCount}" }
    }

    override fun onOpen() {
        ui.refresh { ui.data.t3.refreshUsage() }
    }

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val usages = ui.data.t3.usage
            if (usages.isEmpty()) {
                frame(ui, "Usage", "gauge", t)
                tx("No usage data", CX, TOP)
                return@with
            }
            page = page.coerceIn(0, usages.lastIndex)
            val u = usages[page]
            frame(
                ui,
                "Usage · " + u.provider,
                "gauge",
                t,
                "${page+1}/${usages.size}",
                listOf(u.provider),
            )
            var y = TOP - 2
            u.windows.drop(windowPage * 2).take(2).forEach { w ->
                tx(w.label, CX, y)
                txR("${w.usedPercent}%", W - 16, y)
                y += lh() + 1
                bar(CX, y, CW - 8, 12, w.usedPercent / 100.0, w.elapsedFraction)
                y += 17
                val reset = resetLabel(w.resetsAt, ui)
                tx("resets " +
                        (if (Regex("\\d+h|\\d+m").containsMatchIn(reset)) "in " else "") +
                        reset +
                        " · " +
                        mapOf(
                            "ahead" to "ahead of pace",
                            "on" to "on pace",
                            "under" to "under pace",
                        )[w.pace], CX, y, "s", tone = SECONDARY)
                y += lh("s") + 5
            }
            if (u.credits.isNotEmpty()) hintText("X banked resets", CX, BOTTOM - 34, "s")
            if (u.windows.size > 2) txR("${windowPage + 1}/${(u.windows.size + 1) / 2}", W - 14, BOTTOM - 34, "s", tone = SECONDARY)
            repeat(usages.size) {
                val cx = CX + CW / 2.0 + (it - (usages.size - 1) / 2.0) * 16
                p.circ(cx, H - 7, if (it == page) 4 else 3, if (it == page) 1 else 2, it == page, tone = if (it == page) PRIMARY else TERTIARY)
            }
        }

    override fun key(k: String): Boolean {
        val usages = ui.data.t3.usage
        if (ui.isText && k in listOf("up", "down")) {
            textPage = (textPage + if (k == "down") 1 else -1).coerceIn(0, ui.textPages(usageText()).lastIndex)
            return true
        }
        if (ui.isText && k in listOf("left", "right")) textPage = 0
        when (k) {
            "right",
            "down" -> {
                if (!ui.isText && windowPage + 1 < ((usages.getOrNull(page)?.windows?.size ?: 0) + 1) / 2) windowPage++
                else if (page < usages.lastIndex) { page++; windowPage = 0 }
            }
            "left",
            "up" -> {
                if (!ui.isText && windowPage > 0) windowPage--
                else if (page > 0) { page--; windowPage = if (ui.isText) 0 else max(0, ((usages.getOrNull(page)?.windows?.size ?: 0) + 1) / 2 - 1) }
            }
            "act" -> {
                val u = usages.getOrNull(page) ?: return true
                if (u.credits.isEmpty()) {
                    ui.flash("No banked resets")
                    return true
                }
                ui.push(
                    ListView(
                        ui,
                        "resets",
                        items = {
                            ui.data.t3.usage
                                .find { it.instanceId == u.instanceId && it.machine == u.machine }
                                ?.credits
                                .orEmpty()
                                .map { c ->
                                    Row(
                                        "${c.label} ×${c.availableCount}",
                                        sub = "",
                                        subLines = layout(c.description, CW - 40, "s")[0],
                                        open = {
                                            ui.perform(after = { ui.pop() }) {
                                                ui.data.t3.resetUsage(
                                                    u.machine,
                                                    u.instanceId,
                                                    c.creditId,
                                                )
                                            }
                                        },
                                    )
                                }
                        },
                        chrome = { t -> frame(ui, "Use a reset", "gauge", t, u.provider) },
                    )
                )
            }
            else -> return false
        }
        return true
    }

    override fun legend() =
        mapOf("left" to "Prev provider", "right" to "Next provider", "act" to "Use a banked reset")

    override fun drawText(tg: TextGrid) {
        val u = ui.data.t3.usage.getOrNull(page)
        textPage = TextChrome.reader(tg, "Usage", u?.provider.orEmpty(),
            gridPages(usageText(), tg.cols, tg.rows - 2), textPage, "<> provider X resets")
    }
}

class T3Environments(ui: Ui) :
    ListView(
        ui,
        "env",
        items = {
            ui.data.t3.environments.map { e ->
                Row(
                    e.label,
                    sub = environmentSubtitle(e),
                    subLines =
                        layout(
                            environmentSubtitle(e),
                            CW - 60,
                            "s",
                        )[0],
                    icon = if (e.online) "check" else "cross",
                    right = if (e.online) "ONLINE" else if (!e.paired || e.needsPairing) "NOT PAIRED" else "OFFLINE",
                )
            }
        },
        chrome = { t -> frame(ui, "Environments", "env", t) },
    ) {
    init {
        refreshHook = { ui.data.t3.refreshEnvironments() }
        textItems = { rows().mapIndexed { i, row -> row.copy(subLines = null,
            sub = ui.data.t3.environments.getOrNull(i)?.let(::environmentSubtitle), right = row.right.lowercase()) } }
    }
}

internal fun environmentSubtitle(e: Environment): String = listOf(e.os,
    if (!e.paired || e.needsPairing) "ask ${io.lampyr.glasses.settings.Names.assistant} to pair"
    else if (e.online) "online" else "offline" +
        (e.lastSeenLabel.ifBlank { e.lastSeen }.takeIf { it.isNotBlank() }?.let { ", last seen $it" } ?: "") +
        (e.cause.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
    if (e.needsRepair) "needs re-pairing" else if (!e.canWrite) "read-only" else "",
).filter { it.isNotBlank() }.joinToString(" · ")

class T3Settled(ui: Ui) :
    ListView(
        ui,
        "settled",
        items = {
            ui.data.t3.settled.take(25).map { th ->
                Row(
                    th.title,
                    sub = "",
                    subLines = layout(th.project, CW - 60, "s")[0],
                    right = age(th.ageSec),
                    open = { ui.push(T3Thread(ui, th.key)) },
                )
            }
        },
        chrome = { t -> frame(ui, "Settled · 25 recent", "archive", t) },
    ) {
    init {
        refreshHook = { ui.data.t3.refreshSettled() }
    }
}

class NewThread(ui: Ui) :
    ListView(
        ui,
        "newt",
        items = {
            val all = ui.data.t3.projects
            // Projects from several machines share titles; name the machine only when there is more than one.
            val manyMachines = all.map { it.machine }.distinct().size > 1
            if (all.isEmpty()) listOf(Row("No projects · check Environments", icon = "env", open = { ui.push(T3Environments(ui)) }))
            else all.map { p ->
                Row(
                    p.title,
                    icon = "t3",
                    right = if (manyMachines) shortMachine(p) else "",
                    open = {
                        ui.push(
                            object :
                                ReaderView(
                                    ui,
                                    "newp",
                                    {
                                        "Hold L and say the first prompt.\n\n/glasses is added automatically so replies come back short and paginated."
                                    },
                                    "New thread · " + p.title,
                                    "plus",
                                ) {
                                override val talk
                                    get() =
                                        TalkTarget(
                                            "New thread: " + p.title,
                                            "new-thread",
                                            send = { text ->
                                                val r = ui.data.t3.newThread(p.machine, p.id, text)
                                                if (r.ok) {
                                                    val index =
                                                        ui.stack.indexOfLast { it.id == "newt" }
                                                    if (index > 0)
                                                        while (ui.stack.size > index) ui.pop()
                                                    // Open the thread that was just started.
                                                    r.id?.let { ui.push(T3Thread(ui, ThreadKey(p.machine, it))) }
                                                }
                                                r
                                            },
                                            context = mapOf("machine" to p.machine, "project" to p.id),
                                        )

                                override val textTitle get() = "New thread"
                                override val textHint get() = "L say B back"
                                override fun textContent() = "Project: " + p.title + "\n" + text()
                                override fun legend() = mapOf("talkH" to "Dictate first prompt")
                            }
                        )
                    },
                )
            }
        },
        chrome = { t -> frame(ui, "New thread in…", "plus", t) },
    ) {
    init {
        refreshHook = { ui.data.t3.refreshProjects() }
    }
}

/**
 * A device name short enough for a list row's right column (rows drop right text that does not fit, which hid
 * "Alex's MacBook Air" and "living-room-pc"): the machine label when it is short, else its alias ("Laptop", "Pc").
 */
internal fun shortMachine(p: io.lampyr.glasses.data.Project): String {
    val label = p.machineLabel.trim()
    if (label.isNotEmpty() && label.length <= 12) return label
    val alias = p.machine.trim()
    return when {
        alias.lowercase() in setOf("vps", "pc", "nas") -> alias.uppercase()
        else -> alias.replaceFirstChar { it.uppercase() }
    }
}
