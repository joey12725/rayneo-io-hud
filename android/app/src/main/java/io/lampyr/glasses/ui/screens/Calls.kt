package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.calls.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*

class PhoneCallScreen(ui: Ui, val callId: String) : Screen(ui, "phone-call:$callId", "phone-call") {
    var page = 0
    private val call get() = ui.data.calls.snapshot.calls.find { it.id == callId }
    private val ringing get() = call?.phase == CallPhase.Ringing
    private val dialing get() = call?.phase == CallPhase.Dialing
    /** Ringing, screening and dialing hold the lens lit; a connected call may fade normally (any button wakes it). */
    override val keepAwake get() = call?.phase in setOf(CallPhase.Ringing, CallPhase.Dialing, CallPhase.Screening)
    /** "mobile · (630) 555-0101" when the contact's number label is known. */
    private fun numberLine(c: PhoneCall?, sep: String = " · ") = c?.number?.let { n -> if (c.label.isNotBlank() && n.isNotBlank()) c.label + sep + n else n }
    private fun hint(): String {
        val s = ui.data.calls.snapshot
        val c = call
        return if (ringing) "Y ${if (c?.answer == true) "answer" else "phone"} / B ${if (c?.end == true) "decline" else "phone"} / X hide"
        else "Y ${if (c?.phase == CallPhase.Holding) "resume" else if (s.audioControl) "mute" else "phone"} / X ${if (s.audioControl) "audio" else "phone"} / B back / + ${if (c?.end == true) "end" else "phone"}"
    }
    private fun textDetail(): String {
        val c = call ?: return "Call ended"
        val s = ui.data.calls.snapshot
        return listOf(c.name, if (ringing) c.number.takeIf { it != c.name }.orEmpty() else
                listOf(c.number.takeIf { it != c.name }?.let { numberLine(c, " ") }.orEmpty(), c.via.removeSuffix(" SIM")).filter { it.isNotBlank() }.joinToString(" . "),
            if (ringing) c.via else "",
            if (ringing) "Waiting for your answer" else if (!s.audioControl) "Mute/audio: use phone"
            else (if (s.muted) "Mic off" else "Mic on") + " . " + s.routeName,
            if (!ringing && c.phase == CallPhase.Holding) "On hold" else "",
            if (!ringing && !s.audioControl) "Timer estimated" else "",
            if (ringing && !c.answer) "Answer on phone" else "",
            if (!ringing && s.limitation == "Mute / audio on phone") "" else s.limitation,
            if (!c.end) "End on phone" else "",
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
    private fun namePages() = pages(call?.name ?: "Call ended", CW - 12, 2, "l")
    private fun bodyPages(): List<List<TextLine>> {
        val c = call
        val s = ui.data.calls.snapshot
        return pages(listOf(numberLine(c),
            if (ringing) "Waiting for your answer" else if (!s.audioControl) "Mute/audio: use phone" else
                (if (s.muted) "Muted" else "Mic on") + " · " + s.routeName,
            if (!ringing && !s.audioControl) "Timer estimated" else ""
        ).filterNotNull().filter { it.isNotBlank() }.joinToString("\n"), CW - 12, 3)
    }
    private fun bitmapPageCount() = maxOf(namePages().size, bodyPages().size)
    override fun draw(g: Gfx, t: Double) = with(g) {
        val c = call
        val s = ui.data.calls.snapshot
        val state = if (ringing) if (s.ongoing != null) "Call waiting" else "Incoming call"
            else if (c?.phase == CallPhase.Holding) "On hold" else if (dialing) "Calling" else "Connected"
        frame(ui, state, "call-phone", if (ringing) t else 0.0,
            right = "", railText = listOf(if (ringing || dialing || c == null) "" else callDuration(c, ui.nowS()),
                railCaption(c?.via.orEmpty().removeSuffix(" SIM")).split(' ').firstOrNull().orEmpty()))
        val names = namePages(); val bodies = bodyPages(); val count = bitmapPageCount()
        page = page.coerceIn(0, count - 1)
        var y = TOP + 4
        names[page.coerceAtMost(names.lastIndex)].forEach { drawLine(it, CX, y, "l"); y += lh("l") }
        y = TOP + 2 * lh("l") + 12
        bodies.getOrNull(page)?.forEach { drawLine(it, CX, y); y += lh() }
        if (count > 1) pageFooter(page, count, CX + CW / 2, BOTTOM - 44)
        hintRow(hint().replace(" / B back", "").replace(" / ", " · "), safety = true)
    }
    override fun drawText(tg: TextGrid) {
        val ps = gridPages(textDetail(), tg.cols, tg.rows - 2)
        page = page.coerceIn(0, ps.lastIndex)
        val c = call
        val s = ui.data.calls.snapshot
        TextChrome.reader(tg, if (ringing) if (s.ongoing != null) "Call waiting" else "Incoming call" else if (dialing) "Calling" else "Phone call",
            if (ringing || dialing) "" else c?.let { callDuration(it, ui.nowS()) }.orEmpty(), ps, page,
            if (ringing) if (c?.answer == true && c.end) "Y answer B decline X hide" else "X hide B back" else if (c?.phase == CallPhase.Holding) "Y resume X audio + end"
            else if (s.audioControl) "Y mute X audio + end" else "+ end B back")
    }
    override fun key(k: String): Boolean {
        if (ui.ignoreCallInput(k)) return true
        val c = call
        when (k) {
            "up", "left" -> page = (page - 1).coerceAtLeast(0)
            "down", "right" -> page = (page + 1).coerceAtMost(if (ui.isText) ui.textPages(textDetail()).lastIndex else bitmapPageCount() - 1)
            "sel" -> if (c != null) ui.perform { if (ringing) ui.data.calls.answer(c.id) else if (c.phase == CallPhase.Holding) ui.data.calls.resume(c.id) else ui.data.calls.mute(!ui.data.calls.snapshot.muted) }
            "act" -> if (ringing) ui.hideCall(callId) else ui.push(CallAudio(ui))
            "back" -> if (ringing && c?.answer == true && c.end) ui.perform { ui.data.calls.end(c.id) } else ui.leaveCall()
            "plus" -> if (!ringing && c != null) ui.perform { ui.data.calls.end(c.id) }
            else -> return false
        }
        return true
    }
    override fun talkTap(): Boolean { ui.flash("Use phone microphone during calls"); return true }
    override fun legend() = if (ringing) mapOf("sel" to "Answer", "back" to "Decline", "act" to "Silence glasses & hide")
        else mapOf("sel" to if (call?.phase == CallPhase.Holding) "Resume call" else "Mute / unmute", "act" to "Audio device", "back" to "Leave (call continues)", "plus" to "Hang up")
}

class CallAudio(ui: Ui) : ListView(ui, "call-audio", 
    hint = "Y pick · B back", items = {
        val s = ui.data.calls.snapshot
        if (!s.audioControl) listOf(Row("Audio on phone", sub = "Companion access required"))
        else if (s.routes.isEmpty()) listOf(Row("Waiting for audio devices"))
        else s.routes.map { r -> Row(r.name, right = if (r.id == s.currentRoute) "current" else "",
            open = { ui.perform(after = { ui.pop() }) { ui.data.calls.route(r.id) } }) }
    }, chrome = { frame(ui, "Call audio", "call-phone", 0.0, railText = listOf("Output device")) }) {
    var namePage = 0
    init { textTitle = { "Call audio" }; textContext = { "" }; textHint = { "Y pick B back" } }
    private fun longName() = rows().getOrNull(cur)?.label.orEmpty()
    private fun audioTextPages() = gridPages(longName(), ui.textDisplay.cols - 1, ui.textDisplay.rows - 2)
    private fun namePages() = if (ui.isText) audioTextPages().map { lines -> lines.map { listOf(Token(w = it)) } }
        else pages(longName(), CW - 24, 4)
    override fun key(k: String): Boolean {
        if (k == "left" || k == "right") {
            namePage = (namePage + if (k == "left") -1 else 1).coerceIn(0, namePages().lastIndex)
            return true
        }
        if (k == "up" || k == "down") namePage = 0
        return super.key(k)
    }
    override fun drawText(tg: TextGrid) {
        if (longName().length <= tg.cols - 9) { super.drawText(tg); return }
        val ps = audioTextPages().map { lines -> lines.mapIndexed { index, line -> (if (index == 0) ">" else " ") + line } }
        namePage = namePage.coerceIn(0, ps.lastIndex)
        TextChrome.reader(tg, "Call audio", if (rows().getOrNull(cur)?.right == "current") "current" else "${cur + 1}/${rows().size}", ps, namePage, "Y pick B back <> read")
    }
    override fun draw(g: Gfx, t: Double) {
        if (tw(longName()) <= CW - 140) { super.draw(g, t); return }
        with(g) {
            frame(ui, "Call audio", "call-phone", 0.0, railText = listOf("Output device"))
            val ps = namePages(); namePage = namePage.coerceIn(0, ps.lastIndex)
            p.rr(CX, TOP, CW, ps[namePage].size * lh() + 12, 6, 2, true)
            var y = TOP + 6
            ps[namePage].forEach { drawLine(it, CX + 12, y, col = BLACK); y += lh() }
            tx("Device ${cur + 1}/${rows().size} / ${rows().getOrNull(cur)?.right.orEmpty()}", CX, BOTTOM - 62, "s", tone = SECONDARY)
            pageFooter(namePage, ps.size, W / 2, BOTTOM - 44)
            hintRow("Y pick · B back · ◀▶ read")
        }
    }
    override fun talkTap(): Boolean = true
}

class CallsHistory(ui: Ui) : ListView(ui, "calls-history", hint = "Y open · B back", items = {
    val missed = ui.data.calls.missed
    if (missed.isEmpty()) listOf(Row("No recent missed calls", icon = "phone"))
    else missed.map { n -> Row(n.body, sub = "Phone / missed call", icon = "phone", open = {
        ui.push(ReaderView(ui, "missed-call", { n.body }, "Missed call", "phone", right = "Phone"))
    }) }
}, chrome = { t -> frame(ui, "Phone", "call-phone", 0.0, railText = listOf("Missed calls")) }) {
    init { textTitle = { "Phone" } }
}

/** Call notices bypass the gateway classifier: they are local telecom events and never disclose caller data to it. */
fun callNotifications(ui: Ui): List<Notification> {
    val s = ui.data.calls.snapshot
    // Calls that do not take over (callAlert Quiet or Silent) ring quietly: no row here (Home's Phone row and Recents still show them).
    val current = (s.calls.firstOrNull { it.phase == CallPhase.Ringing && callAlert(it) !in setOf(CallAlert.Quiet, CallAlert.Silent) } ?: s.ongoing)?.let { listOf(Notification("call-current", "Phone", "phone",
        if (it.phase == CallPhase.Ringing) "Incoming call" else "In call", it.name, 1.0, ui.nowMs(), OpenTarget("calls"))) }.orEmpty()
    return current + ui.data.calls.missed + ui.data.notifications.items
}
