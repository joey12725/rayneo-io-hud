package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.Person
import io.lampyr.glasses.ui.screens.PersonCard
import kotlinx.serialization.json.*

/** People (voice recognition of known people): who was heard and seen recently, a person's notes, mute, open a card. */
object PeopleOps : OpProvider {
    private fun everyone(ui: io.lampyr.glasses.ui.Ui) = (ui.data.people.snapshot.recent + ui.data.people.snapshot.heard).distinctBy { it.id }

    private fun AgentCall.person(): Person = resolve(str("person"), everyone(ui), "person", "people_recent", { it.id }, { it.name })

    private fun personJson(p: Person) = buildJsonObject {
        put("id", p.id); put("name", p.name); putText("where", p.where); putText("seenAt", p.seenAt)
        if (p.muted) put("muted", true)
    }

    override fun ops() = listOf(
        OpSpec(
            "people.recent", "List recent people",
            "Known people the glasses heard in the last 10 minutes and recently seen people (where and when), with ids. heard is empty while passive listening is off.",
            params = listOf(Param.boolean("include_muted", "Also list muted people")),
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            ui.data.people.refresh()
            val s = ui.data.people.snapshot
            ok(data = buildJsonObject {
                put("listening", ui.listening.on)
                put("heard", JsonArray((if (ui.listening.on) s.heard else emptyList()).take(MAX_ITEMS).map(::personJson)))
                put("recent", JsonArray(s.recent.filter { bool("include_muted") || !it.muted }.take(MAX_ITEMS).map(::personJson)))
                ui.data.people.notice?.let { put("notice", it) }
            })
        },

        OpSpec(
            "people.lookup", "Look up a person", "One known person's card: where and when last seen, and the user's notes about them (id or name).",
            params = listOf(Param.string("person", "Person id or name", required = true, maxLength = 200)),
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            val p = person()
            ui.data.people.refreshPerson(p.id)
            val fresh = everyone(ui).find { it.id == p.id } ?: p
            ok(data = buildJsonObject {
                personJson(fresh).forEach { (k, v) -> put(k, v) }
                put("notes", clip(fresh.notes))
            })
        },

        OpSpec(
            "people.mute", "Mute a person",
            "Mute (or unmute) a person: muted people are not announced when heard and leave the recent list.",
            params = listOf(
                Param.string("person", "Person id or name", required = true, maxLength = 200),
                Param.boolean("muted", "false to unmute (default true)"),
            ),
            risk = Risk.ACT, tool = false,
        ) {
            val p = person()
            val muted = bool("muted", true)
            done(ui.data.people.mute(p.id, muted), if (muted) "Muted ${p.name}" else "Unmuted ${p.name}")
        },

        OpSpec(
            "people.open", "Show a person's card", "Show one person's card (notes) on the glasses.",
            params = listOf(Param.string("person", "Person id or name", required = true, maxLength = 200)),
            risk = Risk.NAVIGATE, tool = false, available = ::lockedReason,
        ) {
            val p = person()
            show(PersonCard(ui, p.id))
            ok("Showing ${p.name}", registry.context())
        },
    )
}
