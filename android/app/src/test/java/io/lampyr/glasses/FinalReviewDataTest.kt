package io.lampyr.glasses

import android.content.Context
import android.content.ContextWrapper
import io.lampyr.glasses.data.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinalReviewDataTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = temp.root
    }
    private class Gateway : GatewayClient("https://fixture.example", "test") {
        val rows = ConcurrentHashMap<String, JsonElement>()
        override suspend fun get(path: String) = rows[path] ?: JsonArray(emptyList())
        override suspend fun write(path: String, body: JsonElement, method: String) = obj()
    }
    private fun json(text: String) = gatewayJson.parseToJsonElement(text)

    // T3 notifications now come from the gateway's notifier (events); the phone's thread poll must never add any.
    @Test fun t3ThreadPollNeverCreatesNotificationsTheGatewayOwnsThem() = runTest {
        val g = Gateway()
        val data = LiveDataStore(context(), backgroundScope, g, startBackground = false)
        var revision = 0
        suspend fun refresh(vararg states: Pair<String, String>) {
            revision++
            g.rows["/t3/threads"] = json("""{"threads":[${states.joinToString { (id, status) ->
                """{"machine":"m","id":"$id","title":"$id","project":"p","provider":"Codex","status":"$status","updatedAt":"$revision"}"""
            }}]}""")
            assertTrue(data.t3.refreshThreads().ok)
        }
        try {
            for (id in listOf("a", "b", "working", "waiting", "input", "approval", "request")) data.t3.watch("m", id, id, true)
            refresh()
            refresh("a" to "idle", "b" to "working")
            refresh("a" to "done", "b" to "input")
            val progress = listOf("working", "waiting", "input", "approval", "request")
            refresh(*progress.map { it to it }.toTypedArray())
            refresh(*progress.map { it to "done" }.toTypedArray())
            assertTrue(data.notifications.items.isEmpty())
            assertEquals(5, data.t3.threads.size)
        } finally { data.close() }
    }

    @Test fun interpolationStopsAtEndAndDoesNotAdvancePausedOrUnknownDurationIncorrectly() {
        val track = NowPlaying(source = "spotify", positionSec = 50.0, durationSec = 60.0, playing = true)
        assertTrue(GatewayMappings.interpolate(track, 9.0).playing)
        for (elapsed in listOf(10.0, 100.0)) {
            val ended = GatewayMappings.interpolate(track, elapsed)
            assertEquals(60.0, ended.positionSec, 0.0)
            assertFalse(ended.playing)
        }
        val paused = GatewayMappings.interpolate(track.copy(playing = false), 100.0)
        assertFalse(paused.playing); assertEquals(50.0, paused.positionSec, 0.0)
        assertTrue(GatewayMappings.interpolate(track.copy(durationSec = 0.0), 100.0).playing)
    }

    @Test fun coldCacheDoesNotPlayOrInterpolateAndPhoneSessionOwnsEvenPausedState() = runTest {
        val track = NowPlaying(source = "spotify", title = "Remote", positionSec = 50.0, durationSec = 60.0, playing = true, device = "Speaker", volume = 80)
        SnapshotCache(File(temp.root, "gateway-cache")).put("/spotify/now", gatewayJson.encodeToJsonElement(track))
        var clock = 100.0
        val g = Gateway().apply { rows["/spotify/now"] = gatewayJson.encodeToJsonElement(track) }
        val data = LiveDataStore(context(), backgroundScope, g, nowS = { clock }, startBackground = false)
        try {
            assertFalse(data.media.nowPlaying!!.playing)
            assertEquals(50.0, data.media.nowPlaying!!.positionSec, 0.0)
            data.media.refresh()
            clock += 11
            assertFalse(data.media.nowPlaying!!.playing)
            assertEquals(60.0, data.media.nowPlaying!!.positionSec, 0.0)
            val snapshot = MediaHub::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
            for (source in listOf("spotify", "youtube")) for (playing in listOf(true, false)) {
                val phone = NowPlaying(source = source, title = "Phone", playing = playing, device = "Phone", volume = 3)
                snapshot.set(data.mediaHub, phone)
                assertEquals(phone, data.media.nowPlaying)
            }
        } finally { data.close() }
    }

    @Test fun messagePageStateIsPublishedAcrossCoroutineAndRenderThreads() = runTest {
        val g = Gateway().apply { rows["/beeper/chats/c/messages?page=true"] = json("""{"messages":[],"nextBefore":"next"}""") }
        val data = LiveDataStore(context(), backgroundScope, g, startBackground = false)
        try {
            val states = LiveDataStore::class.java.getDeclaredField("messageStates").apply { isAccessible = true }.get(data)
            assertTrue(states is ConcurrentHashMap<*, *>)
            withContext(Dispatchers.Default) { assertTrue(data.beeper.refreshMessages("c").ok) }
            assertTrue(data.beeper.pageState("c").loaded)
            assertEquals("next", data.beeper.pageState("c").nextBefore)
            assertFalse(data.beeper.pageState("c").loadingOlder)
        } finally { data.close() }
    }

    @Test fun idsNeedDottedServersAndPhoneNetworkSingleChatTitlesKeepDigits() = runTest {
        assertEquals("@bob:thanks for that", GatewayMappings.scrubIds("@bob:thanks for that"))
        assertEquals("@bob:thanks", GatewayMappings.safeName("@bob:thanks"))
        assertEquals("hi @someone", GatewayMappings.scrubIds("hi @bob:server.tld"))
        assertEquals("Someone", GatewayMappings.safeName("@bob:server.tld"))
        val networks = listOf("SMS", "Google Messages", "iMessage", "WhatsApp", "Signal", "Telegram")
        for (network in networks) {
            val chat = GatewayMappings.chats(json("""[{"id":"c","name":"15551234567","network":"$network","type":"single"}]""")).single()
            assertEquals(network, "15551234567", chat.name)
            val group = GatewayMappings.chats(json("""[{"id":"c","name":"12345","network":"$network","type":"group"}]""")).single()
            assertEquals("Chat", group.name)
        }
        assertEquals("Chat", GatewayMappings.chats(json("""[{"id":"c","name":"12345","network":"Instagram"}]""")).single().name)
        val data = LiveDataStore(context(), backgroundScope, Gateway(), startBackground = false)
        try {
            data.phoneNotification("n", NoticeInput(kind = "beeper", source = "com.beeper.android", app = "Beeper", title = "15551234567", body = "hi @bob:thanks", network = "SMS"))
            assertEquals("15551234567", data.notifications.items.single().title)
            assertEquals("hi @bob:thanks", data.notifications.items.single().body)
        } finally { data.close() }
    }
}
