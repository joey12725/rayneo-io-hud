package io.lampyr.glasses

import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.avatar.AvatarStates

/** Deterministic bridge snapshots shared by text and simulator bitmap references. */
/** BEEP-PRI fixture, mirrored by PRIORITY_CHATS in simulator/index.html (a glasses pin, two important chats, plain
 * unread, read, and two quiet ones). */
val PRIORITY_CHATS = listOf(
    Chat("p-maya", "Maya", "iMessage", 0, Message("pm", "You", true, "see you at 7"), glassesPinned = true),
    Chat("p-jordan", "Jordan", "Signal", 1, Message("pj", "Jordan", false, "sent the photos from Saturday")),
    Chat("p-hood", "Neighborhood", "WhatsApp", 39, Message("ph", "Pat", false, "lost cat on Elm St"), type = "group", isGroup = true, muted = true),
    Chat("p-sam", "Sam", "iMessage", 2, Message("ps", "Sam", false, "are you still coming tonight?")),
    Chat("p-deals", "Deals", "SMS", 3, Message("pd", "Deals", false, "50% off today only, reply STOP to end"), spam = true),
    Chat("p-crew", "Climbing crew", "WhatsApp", 5, Message("pc", "Alex", false, "@Robin you in for Sunday"), type = "group", isGroup = true, mentions = 1),
    Chat("p-family", "Family", "WhatsApp", 0, Message("pf", "Mom", false, "Dinner Sunday at 6"), type = "group", isGroup = true),
)

fun priorityGoldenData(data: DataStore): DataStore = object : DataStore by data {
    override val beeper = object : BeeperRepository by data.beeper {
        override val chats = PRIORITY_CHATS
        override val unreadTotal get() = chats.count { it.countsTowardUnread }
        override val unreadChats get() = unreadTotal
        override val hasMoreChats get() = false
        override fun chat(chatId: String) = chats.find { it.id == chatId }
        override suspend fun find(query: String) = BeeperPriority.find(query, chats).let { BeeperFind(it, BeeperPriority.confident(it)) }
    }
}

fun callGoldenData(data: DataStore, name: String): DataStore {
    if (name.startsWith("beeper-priority") || name in setOf("beeper-quiet", "beeper-menu", "beeper-find")) return priorityGoldenData(data)
    if (name == "spotify-playing") return object : DataStore by data {
        override val media = object : MediaRepository by data.media {
            override val nowPlaying = data.media.nowPlaying?.copy(source = "spotify", title = "Midnight City", artist = "M83")
        }
    }
    if (name == "report-continuation") return object : DataStore by data {
        override val report = object : ReportRepository by data.report {
            override val snapshot = data.report.snapshot.let { report -> report.copy(cards = report.cards.mapIndexed { index, card ->
                if (index == 0) card.copy(body = (1..80).joinToString(" ") { "word$it" }) else card
            }) }
        }
    }
    if (name.startsWith("memo-recording")) return object : DataStore by data {
        override val memos = object : MemosRepository by data.memos {
            override val snapshot get() = data.memos.snapshot.copy(recording = true, since = -42.0)
        }
    }
    if (!name.startsWith("joy-call") && !name.startsWith("voice-call")) return data
    val status = when {
        name.endsWith("thinking") -> "thinking"
        name.endsWith("speaking") -> "speaking"
        name.endsWith("reconnecting") -> "reconnecting"
        else -> "listening"
    }
    val mode = if (name.endsWith("turns")) "turns" else "live"
    val worker = if (name.endsWith("worker")) JoyWorker("tool", "web_search", "weather in Springfield") else null
    return object : DataStore by data {
        override val joy = object : JoyRepository by data.joy {
            override val snapshot = JoyState(status = status, anim = if (status in AvatarStates.STATUS) status else AvatarStates.LISTENING)
            override val call = JoyCallState(active = true, mode = mode, muted = name.endsWith("muted"), worker = worker)
            override suspend fun startVoiceCall() = ActionResult()
            override suspend fun endVoiceCall() = ActionResult()
        }
    }
}
