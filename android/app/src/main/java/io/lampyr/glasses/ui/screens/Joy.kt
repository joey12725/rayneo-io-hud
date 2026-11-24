package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.render.*
import io.lampyr.glasses.render.avatar.AvatarStates
import io.lampyr.glasses.render.avatar.drawAvatar
import io.lampyr.glasses.ui.*
import kotlin.math.*

class Joy(ui: Ui) : Screen(ui, "joy") {
    private val state
        get() = ui.data.joy.snapshot

    private fun replyText() = state.text
    /** Stay lit while the assistant listens or works on a reply the wearer just asked for. */
    override val keepAwake get() = state.status in setOf("listening", "thinking", "transcribing")
    fun textPages() = ui.textPages(textReply())
    private fun textReply(): String = when (state.status) {
        "idle", "speaking" -> replyText()
        "listening" -> "Listening...\n" + replyText()
        else -> TextChrome.fit(state.status.replaceFirstChar { it.uppercase() } + state.prompt?.let { " . $it" }.orEmpty(), ui.textDisplay.cols) + "\n" + replyText()
    }
    private fun jpages() = if (ui.isText) textPages().map { lines -> lines.map { listOf(Token(w = it)) } } else
        pages(replyText(), W - 162, (H - 58) / lh())

    override fun onOpen() {
        ui.refresh { ui.data.joy.refresh() }
    }

    /** Tap L2 toggles quiet wait here; the L2 hold stays free, so an assigned Hold L2 shortcut also works on the assistant screen. */
    override fun overrides() = listOf(ButtonOverride(QUIET, onTap = {
        ui.quiet = !ui.quiet
        ui.flash(if (ui.quiet) "Quiet wait on" else "Quiet wait off")
    }))

    companion object {
        val QUIET = ButtonClaim("l2", "Quiet wait on/off", tap = true, hold = false)
    }

    override val needsAnimation
        get() = !(ui.quiet && state.status !in listOf("speaking", "idle", "listening"))

    override val talk
        get() = TalkTarget("${io.lampyr.glasses.settings.Names.assistant}", "joy", true, { ui.data.joy.send(it, ui.quiet) })

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val j = state
            val busy = j.status !in listOf("speaking", "idle")
            if (ui.quiet && busy) {
                if (j.status == "listening") icon("mic", W / 2 - 14, H - 40, 28, -t)
                return@with
            }
            drawAvatar(if (j.status == "listening") AvatarStates.LISTENING else j.anim, j.since, t)
            val ps = jpages()
            ui.joyPage = ui.joyPage.coerceIn(0, ps.lastIndex)
            var y = 14
            ps[ui.joyPage].forEach {
                drawLine(it, 150, y)
                y += lh()
            }
            if (ps.size > 1) {
                val s =
                    (if (ui.joyPage > 0) "< " else "  ") +
                        "${ui.joyPage+1}/${ps.size}" +
                        (if (ui.joyPage < ps.lastIndex) " >" else "  ")
                tx(s, floor((150 + W - 12) / 2.0 - tw(s) / 2.0), H - 24, tone = SECONDARY)
            }
            if (busy && j.status != "listening") tx(fit(j.status + " · " + j.prompt.orEmpty(), W - 162, "s"), 150, H - 44, "s", tone = SECONDARY)
            if (ui.quiet) txR("QUIET", W - 10, H - 24, "s", tone = SECONDARY)
            if (j.status == "listening") {
                icon("mic", 126, H - 28, 20, -t)
            }
        }

    override fun key(k: String): Boolean {
        when (k) {
            "down",
            "right" -> ui.joyPage = min(jpages().lastIndex, ui.joyPage + 1)
            "up",
            "left" -> ui.joyPage = max(0, ui.joyPage - 1)
            "act" -> ui.push(JoyMenu(ui))
            else -> return false
        }
        return true
    }

    override fun legend() =
        mapOf(
            "up" to "Prev page",
            "down" to "Next page",
            "left" to "Prev page",
            "right" to "Next page",
            "act" to "Menu: voice call · new conversation",
            "quiet" to "Quiet wait ${if(ui.quiet)"off" else "on"} (hide screen until reply)",
        )

    override fun drawText(tg: TextGrid) {
        val j = state
        val busy = j.status !in listOf("speaking", "idle")
        if (ui.quiet && busy) {
            if (j.status == "listening") tg.put(0, tg.rows - 1, "* LISTENING")
            return
        }
        val status = when (j.status) {
            "idle", "speaking" -> if (ui.quiet) "QUIET" else "Reply"
            "listening" -> "* LISTENING"
            else -> j.status.replaceFirstChar { it.uppercase() }
        }
        ui.joyPage = TextChrome.reader(tg, io.lampyr.glasses.settings.Names.assistant, status,
            gridPages(textReply(), tg.cols, tg.rows - 2), ui.joyPage, "X menu L talk")
    }
}

class JoyMenu(ui: Ui) :
    ListView(
        ui,
        "joymenu",
        items = {
            listOf(
                Row(
                    "Voice call",
                    icon = "mic",
                    open = {
                        ui.pop()
                        ui.push(JoyCall(ui))
                    },
                ),
                Row(
                    "New conversation",
                    icon = "plus",
                    open = {
                        ui.perform(
                            after = {
                                ui.joyPage = 0
                                ui.pop()
                            }
                        ) {
                            ui.data.joy.newConversation()
                        }
                    },
                ),
                // the assistant call layout: compact (default) keeps most of the lens dark and see-through; full is the old screen.
                Row(
                    "Call layout",
                    icon = "glasses",
                    right = if (ui.powerStore.prefs.joyCallLayout == "full") "Full" else "Compact",
                    selLabel = "Change",
                    open = {
                        val p = ui.powerStore.prefs
                        ui.powerStore.update(p.copy(joyCallLayout = if (p.joyCallLayout == "full") "compact" else "full"))
                        ui.requestRender()
                    },
                ),
            )
        },
        chrome = { t -> frame(ui, io.lampyr.glasses.settings.Names.assistant, "avatar-portrait", t) },
    )

/**
 * Live state comes from the bridge; fallback retains paginated dictation replies.
 *
 * Two layouts (pref power.joyCallLayout, toggled in the assistant's X menu): compact, the default, is a small card in the
 * bottom-left corner (the assistant, a smaller worker portrait, timer, state, mute and the hang-up hint) so the rest of the
 * see-through lens stays dark; full is the original screen. Turn-based calls always use full (they show replies).
 * Locking the lens never ends the call; B (hang up) is the only way out from here.
 */
class JoyCall(ui: Ui) : Screen(ui, "call") {
    override val keepAwake get() = ui.data.joy.call.active
    private var replyPage = 0
    private val call get() = ui.data.joy.call
    private val turns get() = call.mode == "turns"
    val compact get() = !turns && ui.powerStore.prefs.joyCallLayout != "full"
    private fun stateText(): String = ui.data.joy.snapshot.let {
        if (call.mode == "connecting") "Connecting…"
        else if (turns) when (it.status) {
            "listening" -> "Listening · six-second turn"
            "speaking", "idle" -> it.text
            else -> it.prompt?.let { prompt -> "> $prompt" } ?: "Transcribing…"
        } else when (it.status) {
            "speaking" -> "${io.lampyr.glasses.settings.Names.assistant} is talking"
            "thinking" -> "Thinking…"
            "reconnecting" -> "Reconnecting…"
            "compacting" -> "Refreshing context…"
            else -> if (call.muted) "Microphone muted" else "Listening…"
        }
    }
    /** "Main Hermes · searching · weather in Springfield" while the working agent runs a handed-off task. */
    fun workerText(): String? = call.worker?.let { w -> "${io.lampyr.glasses.settings.Names.worker} · " + CallAnimations.workerLabel(w) + (w.task?.let { " · $it" } ?: "") }
    private fun voiceAnim() = if (call.mode == "connecting") CallAnimations.VOICE.getValue("connecting") else
        CallAnimations.voice(ui.data.joy.snapshot.status, call.muted, call.worker, ui.nowS() - call.workerSince)
    private fun textCallState() = (if (turns) "Turn-based\n" else "") + stateText() + (workerText()?.let { "\n$it" } ?: "")
    private val hint get() = if (turns) "X info · B hang up" else "X ${if (call.muted) "unmute" else "mute"} · B hang up"
    override fun onOpen() { ui.perform {
        val result = ui.data.joy.startVoiceCall()
        if (!result.ok && ui.top === this@JoyCall) ui.pop()
        result
    } }
    override fun onClose() { if (call.active) ui.refresh { ui.data.joy.endVoiceCall() } }
    override fun draw(g: Gfx, t: Double) = with(g) {
        val s = floor(t - t0).toInt().coerceAtLeast(0)
        val j = ui.data.joy.snapshot
        if (compact) { drawCompact(this, s, t); return@with }
        drawAvatar(voiceAnim(), j.since, t)
        call.worker?.let { w -> CallAnimations.worker(w)?.let { drawAvatar(it, call.workerSince, t, W - 14 - 32, 60, 32, 48) } }
        workerText()?.let { tx(fit(it, W - 162 - 40, "s"), 150, BOTTOM - 40, "s", tone = SECONDARY) }
        tx("Voice call", 150, 14, "l")
        if (turns) tx("turn-based", 150, 14 + lh("l") + 4, "s", tone = SECONDARY)
        tx("${s/60}:${(s%60).toString().padStart(2,'0')}", 150, 68, "l")
        val ps = pages(stateText(), W - 162, 3)
        replyPage = replyPage.coerceIn(0, ps.lastIndex)
        var y = 110
        ps[replyPage].forEach { drawLine(it, 150, y); y += lh() }
        if (ps.size > 1) txR("${replyPage + 1}/${ps.size}", W - 14, BOTTOM - 40, "s", tone = SECONDARY)
        p.fr(150, BOTTOM - 22, W - 162, 1, tone = TERTIARY)
        hintText(hint, 150, BOTTOM - 16, "s", safety = true)
    }
    /** Compact card: rows BOTTOM-72..BOTTOM, x 8..[CARD_X]+[CARD_W]; everything else stays black (see-through). */
    private fun drawCompact(g: Gfx, s: Int, t: Double) = with(g) {
        val y0 = BOTTOM - 72
        drawAvatar(voiceAnim(), ui.data.joy.snapshot.since, t, 8, y0, 48, 72)
        call.worker?.let { w -> CallAnimations.worker(w)?.let { drawAvatar(it, call.workerSince, t, 60, y0, 24, 36) } }
        val head = "Voice call · ${s / 60}:${(s % 60).toString().padStart(2, '0')}" + if (call.muted) " · muted" else ""
        tx(fit(head, CARD_W, "m"), CARD_X, y0, "m")
        tx(fit(stateText(), CARD_W, "m"), CARD_X, y0 + 18, "m", tone = SECONDARY)
        // The card is too narrow for the task text; the worker portrait and its verb carry the meaning.
        call.worker?.let { tx(fit("${io.lampyr.glasses.settings.Names.worker} · " + CallAnimations.workerLabel(it), CARD_W, "s"), CARD_X, y0 + 36, "s", tone = SECONDARY) }
        hintText(hint, CARD_X, BOTTOM - 16, "s", safety = true)
    }
    companion object {
        const val CARD_X = 92
        const val CARD_W = 300
    }

    override fun key(k: String): Boolean {
        when (k) {
            "left", "up", "right", "down" -> {
                val last = if (ui.isText) ui.textPages(textCallState()).lastIndex else pages(stateText(), W - 162, 3).lastIndex
                replyPage = (replyPage + if (k in listOf("right", "down")) 1 else -1).coerceIn(0, last)
            }
            "act" -> if (turns) ui.flash("Turn-based: mute unavailable") else ui.perform { ui.data.joy.muteVoiceCall() }
            "back" -> { ui.perform {
                try { ui.data.joy.endVoiceCall() }
                finally { if (!call.active && ui.top === this@JoyCall) ui.pop() }
            } }
            else -> return false
        }
        return true
    }
    override fun legend() = mapOf("back" to "Hang up", "act" to if (turns) "Turn-based call info" else if (call.muted) "Unmute" else "Mute")
    override fun drawText(tg: TextGrid) {
        replyPage = TextChrome.reader(tg, "Voice call", fmtS(ui.nowS() - t0),
            gridPages(textCallState(), tg.cols, tg.rows - 2), replyPage,
            if (turns) "X info B hang up" else "X ${if (call.muted) "unmute" else "mute"} B hang up")
    }
}
