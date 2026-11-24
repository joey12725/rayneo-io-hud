package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*

class People(ui: Ui) :
    ListView(
        ui,
        "people",
        items = {
            listOf(
                Row(
                    "Heard in last 10 min",
                    icon = "mic",
                    right = if (ui.listening.on) ui.data.people.snapshot.heard.size.toString() else "Off",
                    open = { ui.push(QuickPeople(ui)) },
                ),
                Row("RECENT", sep = true),
            ) +
                ui.data.people.snapshot.recent.filter { !it.muted }.map { p ->
                    Row(
                        p.name,
                        sub = "",
                        subLines = layout(p.where, CW - 60, "s")[0],
                        right = p.seenAt,
                        icon = "people",
                        open = { ui.push(PersonCard(ui, p.id)) },
                    )
                } + listOfNotNull(ui.data.people.notice?.let { Row(it, sep = true) }) +
                listOf(Row("Muted (${ui.data.people.snapshot.recent.count { it.muted }})", icon = "people",
                    open = { ui.push(MutedPeople(ui)) }))
        },
        chrome = { t -> frame(ui, "People", "people", t, railText = listOf("Known only")) },
    ) {
    init {
        refreshHook = { ui.data.people.refresh() }
        textItems = { rows().map { row -> row.copy(right = relativeSeen(row.right, ui)) } }
        animateChrome = false
    }
}

class MutedPeople(ui: Ui) : ListView(ui, "people-muted", items = {
    ui.data.people.snapshot.recent.filter { it.muted }.map { p ->
        Row(p.name, icon = "people", open = { ui.push(PersonCard(ui,p.id)) })
    }
}, chrome = { t -> frame(ui,"Muted people","people",t) }) {
    init { textTitle = { "Muted people" }; animateChrome = false; refreshHook = { ui.data.people.refresh() } }
}

open class PersonCard(ui: Ui, private val personId: String, private val cycle: Boolean = false) :
    ReaderView(
        ui,
        if (cycle) "pq" else "card",
        text = { (ui.data.people.snapshot.recent + ui.data.people.snapshot.heard).find { it.id == personId }?.notes.orEmpty() },
        fullWidth = true,
        y0off = lh("s") + 6,
        rows = (BOTTOM - TOP - lh("s") - 6 - 26) / lh(),
    ) {
    private val person
        get() =
            ui.data.people.snapshot.recent.find { it.id == personId }
                ?: ui.data.people.snapshot.heard.find { it.id == personId }
    override fun onOpen() { ui.refresh { ui.data.people.refreshPerson(personId) } }

    override val talk
        get() =
            TalkTarget(
                "Remind me: " + person?.name?.substringBefore(' '),
                "reminder",
                send = { ui.data.people.remind(personId, it).let { result ->
                    if (result.ok) result.copy(message = "Reminder armed") else result
                } },
            )

    override fun header(g: Gfx, t: Double) {
        val p = person ?: return
        with(g) {
            frameW(ui, p.name, p.seenAt)
            tx(caseText(p.where), X0, TOP - 2, "s", tone = SECONDARY)
        }
    }

    override val textTitle get() = person?.name ?: "Person"
    override val textContext get() = relativeSeen(person?.seenAt.orEmpty(), ui)
    override val textHint get() = if (cycle) "<> person X count L remind" else "X count L remind B back"
    override fun textContent() = person?.let { "Met: " + it.where + "\n" + it.notes }.orEmpty()

    override fun footer(g: Gfx, n: Int) {
        with(g) {
            if (cycle) {
                val r = ui.data.people.snapshot.heard
                hintText(
                    "◀ ${r.indexOfFirst { it.id==personId }+1}/${r.size} heard ▶",
                    X0,
                    BOTTOM - 16,
                    "s",
                )
            }
            hintText("X actions", if (cycle) X0 + 240 else X0, BOTTOM - 16, "s")
            if (n > 1) txR("${page+1}/$n ▼", W - 12, BOTTOM - 16, "s", tone = SECONDARY)
        }
    }

    override fun key(k: String): Boolean {
        if (k == "act") {
            val muted = person?.muted == true
            val label = if (muted) "Count this person again" else "Don't count this person"
            ui.push(ListView(ui, "personact", items = { listOf(Row(label, icon = "people", open = {
                ui.push(ListView(ui, "personconfirm", items = { listOf(
                    Row("Confirm", icon = "check", open = { ui.perform(after = { ui.pop(); ui.pop() }) { ui.data.people.mute(personId, !muted) } }),
                    Row("Cancel", icon = "cross", open = { ui.pop() })) },
                    chrome = { t -> frame(ui, label, "people", t) }).apply { textTitle = { label } })
            })) }, chrome = { t -> frame(ui, "Person actions", "people", t) }).apply {
                textTitle = { "Person actions" }; textWrapFocus = true; animateChrome = false
            })
            return true
        }
        if (cycle && (k == "left" || k == "right")) {
            val r = ui.data.people.snapshot.heard
            if (r.isNotEmpty()) {
                val i =
                    (r.indexOfFirst { it.id == personId } +
                        (if (k == "right") 1 else -1) +
                        r.size) % r.size
                ui.replaceTop(QuickPeople(ui, i))
            }
            return true
        }
        if (k in listOf("up", "down") || (!cycle && k in listOf("left", "right"))) return super.key(k)
        return false
    }

    override fun legend() =
        mapOf(
            "up" to "Prev page",
            "down" to "More about them",
            "talkH" to "Reminder for next time",
            "act" to "Count / don't count",
        ) +
            (if (cycle) mapOf("left" to "Previous person", "right" to "Next person")
            else emptyMap())
}

class QuickPeople(ui: Ui, private val index: Int = 0) : Screen(ui, "pq") {
    override val needsAnimation
        get() = false

    private var personId: String? = null
    private var cachedCard: PersonCard? = null
    private val card: PersonCard?
        get() {
            // AMB-14: voice matching is off while passive listening is muted.
            val heard = if (ui.listening.on) ui.data.people.snapshot.heard else emptyList()
            val id = heard.getOrNull(index.coerceAtMost((heard.size - 1).coerceAtLeast(0)))?.id
            if (id != personId) {
                personId = id
                cachedCard = id?.let { PersonCard(ui, it, true) }
            }
            return cachedCard
        }

    override fun onOpen() {
        ui.refresh { ui.data.people.refresh() }
    }

    override fun draw(g: Gfx, t: Double) {
        val current = card
        if (current != null) current.draw(g, t)
        else
            with(g) {
                frameW(ui, "People nearby", "")
                var y = TOP
                layout(emptyText(), CW - 12).forEach {
                    drawLine(it, CX, y)
                    y += lh()
                }
            }
    }

    override fun key(k: String) = card?.key(k) ?: false

    override fun legend() = card?.legend().orEmpty()

    override val talk
        get() = card?.talk

    override fun drawText(tg: TextGrid) {
        val current = card
        if (current != null) current.drawText(tg)
        else TextChrome.reader(tg, "People nearby", "", gridPages(
            emptyText(), tg.cols, tg.rows - 2), 0)
    }

    private fun emptyText() =
        if (ui.listening.on) "No one you know has been heard in the last 10 minutes."
        else "Passive listening is off, so voices aren't being matched. Hold minus to turn it back on."
}

private fun relativeSeen(value: String, ui: Ui): String = try {
    age(((ui.nowMs() - java.time.Instant.parse(value).toEpochMilli()) / 1000).coerceAtLeast(0)) + " ago"
} catch (_: Exception) { value }
