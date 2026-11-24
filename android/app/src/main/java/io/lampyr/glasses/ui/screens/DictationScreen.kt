package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.audio.DictationEvent
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import kotlin.math.*

class DictationScreen(ui: Ui, val target: TalkTarget) : Screen(ui, "dict") {
    var phase = "idle"
        private set
    override val keepAwake get() = phase in setOf("listen", "trans", "enh")

    var text = ""
        private set

    /** Spoken mentions of every segment so far ("L add" appends), sent with the text on Y. */
    val mentions = mutableListOf<io.lampyr.glasses.data.Mention>()

    var page = 0
    private var l0 = 0.0
    private var n = 1
    private var error: String? = null
    var level = 0f

    fun listen() {
        phase = "listen"
        error = null
        l0 = ui.nowS()
        ui.requestRender()
    }

    fun discardRecording() { phase = "preview"; ui.requestRender() }

    /** Set by the input engine while this screen owns the capture session; ends it without a result. */
    var onCancel: (() -> Unit)? = null

    /** Leaving the screen (B, Home, a cleared stack) ends its live capture or pending transcription cleanly. */
    override fun onClose() {
        if (phase in setOf("listen", "trans", "enh")) onCancel?.invoke()
        onCancel = null
    }

    fun event(event: DictationEvent) {
        when (event) {
            DictationEvent.Transcribing -> phase = "trans"
            DictationEvent.Enhancing -> phase = "enh"
            is DictationEvent.Done -> {
                error = null
                text = if (text.isEmpty()) event.text else text + " " + event.text
                if (event.text.isNotEmpty()) mentions += event.mentions
                phase = "preview"
                page = 0
                if (target.instant && event.text.isNotBlank() && ui.top === this) {
                    // Phone "say a name": act on the words now; the result screen replaces this preview.
                    // Only while the preview is on top: a late transcript must not open a call card elsewhere.
                    val value = text
                    ui.pop()
                    ui.perform { target.send(value) }
                }
                // A lookup target (Beeper "find a chat") acts on the words at once; nothing is sent anywhere. Only while
                // this preview is still on top: B during transcription discards it, and a late result must not then jump.
                else if (target.autoSend && text.isNotBlank() && ui.top === this) key("sel")
            }
            is DictationEvent.Level -> level = event.value
            is DictationEvent.Error -> {
                phase = "preview"
                error = event.message
                ui.flash(error!!)
            }
        }
        ui.requestRender()
    }

    override val needsAnimation
        get() = phase != "preview"

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            frame(ui, target.label, "mic", if (phase == "listen") -t else 0.0, "")
            when (phase) {
                "listen" -> {
                    val s = floor(t - l0).toInt()
                    tx("LISTENING", CX, TOP + 10, "l")
                    tx("0:" + s.toString().padStart(2, '0'), CX, TOP + 10 + lh("l") + 4, "l")
                    repeat(24) {
                        val amplitude = if (ui.data is io.lampyr.glasses.data.LiveDataStore) (level * 6).coerceIn(0f, 1f).toDouble() else 1.0
                        val h = 6 + abs(sin(t * 9 + it * 1.7) * cos(t * 3 + it)) * 44 * amplitude
                        p.fr(CX + it * 16, TOP + 110 - h / 2, 9, h)
                    }
                    hintTextR("release L to stop", W - 14, BOTTOM - 16, "s")
                }
                "trans",
                "enh" -> {
                    tx(if (phase == "trans") "Transcribing…" else "Enhancing…", CX, TOP + 20, "l")
                    icon("spin", CX, TOP + 100, 40, t)
                }
                else -> {
                    val ps = pages(text.ifBlank { error ?: "No speech captured. Hold L to retry." }, CW - 12, (BOTTOM - TOP - 56) / lh())
                    page = page.coerceIn(0, ps.lastIndex)
                    var y = TOP
                    val marks = mentionMarks(ps.flatten(), mentions.map { it.token })
                    val first = ps.take(page).sumOf { it.size }
                    ps[page].forEachIndexed { i, line ->
                        drawLine(line, CX, y)
                        underlineMarks(line, marks[first + i].orEmpty(), CX, y)
                        y += lh()
                    }
                    p.fr(CX, BOTTOM - 36, CW, 2, tone = TERTIARY)
                    hintText(if (text.isBlank()) "L retry · B discard"
                        else if (target.steer != null) "Y queue · X steer · B discard"
                        else "Y send · B discard · L add", CX, BOTTOM - 28, "s")
                    if (ps.size > 1) txR("${page+1}/${ps.size}", W - 14, BOTTOM - 56, "s", tone = SECONDARY)
                    n = ps.size
                }
            }
        }

    override fun key(k: String): Boolean {
        if (k == "back") {
            ui.pop()
            ui.flash("Discarded")
            return true
        }
        if (phase != "preview") return true
        if (k in listOf("sel", "act") && text.isBlank()) { ui.flash(error ?: "Hold L to retry"); return true }
        if (ui.isText) n = ui.textPages(text).size
        when (k) {
            "sel" -> {
                val value = text
                val spoken = mentions.toList()
                val rich = target.sendRich
                ui.perform(after = { if (ui.top === this) ui.pop() }) { if (rich != null && spoken.isNotEmpty()) rich(value, spoken) else target.send(value) }
            }
            "act" -> {
                val steer = target.steer
                if (steer != null) {
                    val value = text
                    ui.perform(after = { if (ui.top === this) ui.pop() }) { steer(value) }
                }
            }
            "down",
            "right" -> page = min(n - 1, page + 1)
            "up",
            "left" -> page = max(0, page - 1)
        }
        return true
    }

    override fun legend() =
        if (phase == "preview")
            mapOf(
                "sel" to if (target.steer != null) "Queue (sends after this turn)" else "Send",
                "back" to "Discard",
                "up" to "Prev page",
                "down" to "Next page",
                "talkH" to "Hold again: add more",
            ) + (if (target.steer != null) mapOf("act" to "Steer (interrupt now)") else emptyMap())
        else mapOf("talkH" to "Release: stop & transcribe")

    override fun drawText(tg: TextGrid) {
        TextChrome.header(tg, target.label)
        when (phase) {
            "listen" -> {
                TextChrome.state(tg, "* LISTENING", "L release B cancel", fmtS(ui.nowS() - l0))
                if (tg.rows >= 7) tg.put(0, 4, TextChrome.bar((level * 6).coerceIn(0f, 1f).toDouble()))
            }
            "trans" -> TextChrome.state(tg, "Transcribing...", "B cancel")
            "enh" -> TextChrome.state(tg, "Enhancing...", "B cancel")
            else -> {
                val ps = gridPages(text.ifBlank { error ?: "No speech captured. Hold L to retry." }, tg.cols, tg.rows - 2)
                n = ps.size
                page = TextChrome.reader(tg, target.label, "Preview", ps, page,
                    if (text.isBlank()) "L retry B cancel" else if (target.steer != null) "Y queue X steer B drop" else "Y send B drop L add")
            }
        }
    }
}

/**
 * Word positions of mention tokens ("@Sam Rivera" may span two words and a line break) in laid-out
 * lines: line index to the token indexes to highlight. A token matches when its words are equal and the
 * last word is followed only by punctuation ("@Taylor," marks "@Taylor,").
 */
fun mentionMarks(lines: List<TextLine>, tokens: List<String>): Map<Int, Set<Int>> {
    val flat = lines.flatMapIndexed { li, line -> line.mapIndexedNotNull { ti, t -> t.w?.let { Triple(li, ti, it) } } }
    val out = mutableMapOf<Int, MutableSet<Int>>()
    for (token in tokens.distinct()) {
        val words = token.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) continue
        for (start in 0..flat.size - words.size) {
            val ok = words.indices.all { k ->
                val w = flat[start + k].third
                if (k < words.lastIndex) w == words[k]
                else w.startsWith(words[k]) && w.substring(words[k].length).none { it.isLetterOrDigit() || it == '_' }
            }
            if (ok) words.indices.forEach { k -> flat[start + k].let { (li, ti) -> out.getOrPut(li) { mutableSetOf() } += ti } }
        }
    }
    return out
}

/** Underline the marked tokens of one line drawn by [drawLine] at the same origin: the mention highlight. */
fun Gfx.underlineMarks(line: TextLine, marks: Set<Int>, x: Number, y: Number, sz: String = "m") {
    if (marks.isEmpty()) return
    var xx = x.toDouble()
    line.forEachIndexed { i, token ->
        if (token.gap) xx += tw(" ", sz)
        val w = if (token.e != null) es(sz) else tw(token.w.orEmpty(), sz)
        if (i in marks) {
            // Underline only the name, not trailing punctuation.
            val word = token.w.orEmpty().trimEnd { !it.isLetterOrDigit() && it != '_' && it != '-' }
            p.fr(xx, y.toDouble() + lh(sz) - 3, tw(word, sz), 2)
        }
        xx += w
    }
}
