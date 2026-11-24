package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.ui.screens.callNotifications
import kotlinx.serialization.json.*

/** Home screen ops. Navigation to apps is generic: glasses.open_app / glasses.list_apps / glasses.home. */
object HomeOps : OpProvider {
    override fun ops() = listOf(
        OpSpec(
            "home.summary", "Read the Home summary",
            "What the user's glasses Home screen summarises: weather, next event, Beeper unread chats, T3 threads needing them, now playing, notifications, call and passive listening.",
            risk = Risk.READ,
        ) {
            val home = ui.data.home
            val np = ui.data.media.nowPlaying
            val calls = ui.data.calls.snapshot
            ok(data = buildJsonObject {
                put("weather", "${home.weather.temperatureLabel} ${home.weather.condition}".trim())
                home.nextEvent?.let { e -> put("nextEvent", buildJsonObject { put("title", e.title); put("start", e.start); if (e.location.isNotBlank()) put("location", e.location) }) }
                put("beeperUnread", home.beeper?.unread ?: ui.data.beeper.unreadTotal)
                put("beeperNames", JsonArray((home.beeper?.names ?: ui.data.beeper.chats.filter { it.unread > 0 }.map { it.name }).take(10).map(::JsonPrimitive)))
                put("t3NeedYou", ui.data.t3.needYouCount)
                put("t3Done", home.t3?.done ?: ui.data.t3.threads.count { it.status == "done" })
                put("nowPlaying", np?.let { buildJsonObject { put("title", it.title); put("source", it.source); put("playing", it.playing) } } ?: JsonNull)
                put("notifications", callNotifications(ui).size)
                put("call", (calls.ringing ?: calls.ongoing)?.let { JsonPrimitive("${it.phase.name.lowercase()}: ${it.name}") } ?: JsonNull)
                put("passiveListening", ui.listening.on)
            })
        },
    )
}
