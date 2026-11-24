package io.lampyr.glasses

import android.content.Context
import android.content.ContextWrapper
import io.lampyr.glasses.audio.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.service.GlassesService
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** VOX context: the phone tells the gateway where a dictation goes, and spoken mentions survive to the send. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceContextTest {
    @get:Rule val temp = TemporaryFolder()

    /** Records the context of each start; implements only the two-argument start like the existing fakes. */
    private class Audio : Dictation {
        val starts = mutableListOf<Pair<String, Map<String, String>>>()
        var listener: ((DictationEvent) -> Unit)? = null
        override fun start(target: String, onEvent: (DictationEvent) -> Unit) = start(target, emptyMap(), onEvent)
        override fun start(target: String, context: Map<String, String>, onEvent: (DictationEvent) -> Unit) { starts += target to context; listener = onEvent }
        override fun stop() {}
        override fun cancel() {}
    }

    private class Timers : Scheduler {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        var now = 0L
        override fun after(delayMs: Long, block: () -> Unit): Scheduled {
            val task = (now + delayMs) to block; tasks += task
            return object : Scheduled { override fun cancel() { tasks.remove(task) } }
        }
        fun advance(ms: Long) { now += ms; tasks.filter { it.first <= now }.forEach { tasks.remove(it); it.second() } }
    }

    @Test fun dictatePathCarriesOnlyKnownNonBlankContextEncoded() {
        assertEquals("/voice/dictate?target=memo", dictatePath("memo"))
        assertEquals("/voice/dictate?target=beeper&chatId=%21abc%3Abeeper.local%2Fx%20y",
            dictatePath("beeper", mapOf("chatId" to "!abc:beeper.local/x y")))
        assertEquals("/voice/dictate?target=t3&machine=desk&thread=t-1",
            dictatePath("t3", linkedMapOf("machine" to "desk", "thread" to "t-1", "secret" to "nope", "project" to " ")))
    }

    @Test fun doneRowMentionsParseAndDropMalformedEntries() {
        val row = gatewayJson.parseToJsonElement("""{"phase":"done","text":"@Taylor are you in?","mentions":[
            {"token":"@Taylor","id":"@discordgo_1:beeper.local","name":"Taylor"},{"token":"Taylor","id":"x"},{"token":"@Sam"},{"id":"y"}]}""")
        assertEquals(listOf(Mention("@Taylor", "@discordgo_1:beeper.local", "Taylor")), Mentions.parse(row))
        assertTrue(Mentions.parse(gatewayJson.parseToJsonElement("""{"phase":"done","text":"hi"}""")).isEmpty())
        // Old gateways and odd shapes: no crash, no mentions.
        assertTrue(Mentions.parse(gatewayJson.parseToJsonElement("""{"mentions":"nope"}""")).isEmpty())
    }

    @Test fun streamedDoneRowWithMentionsReachesTheParser() = runBlocking {
        val f = FixtureGateway()
        f.fixtures["POST /voice/dictate"] = JsonPrimitive("{\"phase\":\"transcribing\"}\n{\"phase\":\"done\",\"text\":\"@Taylor hi\",\"raw\":\"at chaos hi\",\"mentions\":[{\"token\":\"@Taylor\",\"id\":\"@d_1:beeper.local\",\"name\":\"Taylor\"}]}\n")
        var mentions = emptyList<Mention>()
        f.client.stream(dictatePath("beeper", mapOf("chatId" to "chat/a")), PcmAudio.wav(byteArrayOf(0, 0)), "audio/wav") { if (it.s("phase") == "done") mentions = Mentions.parse(it) }
        assertEquals("target=beeper&chatId=chat%2Fa", f.sent.single().query)
        assertEquals(listOf("@Taylor"), mentions.map { it.token })
    }

    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }
    @Test fun liveBeeperSendIncludesMentionsOnlyWhenPresent() = runTest {
        val f = FixtureGateway()
        val data = LiveDataStore(context(), backgroundScope, f.client, { Instant.parse("2026-11-12T20:00:00Z").toEpochMilli() }, { testScheduler.currentTime / 1000.0 }, startBackground = false)
        try {
            assertTrue(data.beeper.send("chat/a", "@Taylor hi", null, listOf(Mention("@Taylor", "@d_1:beeper.local", "Taylor"))).ok)
            val rich = gatewayJson.parseToJsonElement(f.sent.last { it.method == "POST" && it.path.endsWith("/messages") }.body)
            assertEquals("@Taylor hi", rich.s("text"))
            assertEquals("[{\"token\":\"@Taylor\",\"id\":\"@d_1:beeper.local\"}]", rich.a("mentions").toString())
            assertTrue(data.beeper.send("chat/a", "plain", null, emptyList()).ok)
            val plain = gatewayJson.parseToJsonElement(f.sent.last { it.method == "POST" && it.path.endsWith("/messages") }.body).jsonObject
            assertFalse("mentions" in plain)
        } finally { data.close() }
    }

    private fun fixture(block: (Ui, InputEngine, Timers, Audio) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { 100000000L }, scope)
        val ui = Ui(data, scope = scope, nowS = { 0.0 }, nowMs = { 100000000L })
        val timer = Timers(); val audio = Audio()
        val engine = InputEngine(ui, audio, scheduler = timer)
        try { block(ui, engine, timer, audio) } finally { engine.close(); ui.close() }
    }

    @Test fun beeperThreadDictationSendsChatIdAndAddSegmentsKeepIt() {
        fixture { ui, input, timer, audio ->
            val chat = ui.data.beeper.chats.first()
            ui.push(BeeperThread(ui, chat.id))
            input.press("l"); timer.advance(450)
            assertEquals("beeper" to mapOf("chatId" to chat.id), audio.starts.single())
            val screen = ui.top as DictationScreen
            input.release("l")
            audio.listener!!.invoke(DictationEvent.Done("@Taylor one", listOf(Mention("@Taylor", "@d_1:x", "Taylor"))))
            assertEquals("preview", screen.phase)
            // "L add" on the preview keeps the same destination context.
            input.press("l"); timer.advance(450)
            assertEquals("beeper" to mapOf("chatId" to chat.id), audio.starts.last())
        }
    }

    @Test fun t3ThreadDictationSendsMachineAndThread() {
        fixture { ui, input, timer, audio ->
            val thread = ui.data.t3.threads.first()
            ui.push(T3Thread(ui, ThreadKey(thread.machine, thread.id)))
            input.press("l"); timer.advance(450)
            val (target, context) = audio.starts.single()
            assertTrue(target, target == "t3" || target == "other") // "other" when the thread is asking a question
            assertEquals(mapOf("machine" to thread.machine, "thread" to thread.id), context)
        }
    }

    @Test fun previewAccumulatesMentionsAcrossSegmentsAndSendsThemRich() {
        fixture { ui, _, _, _ ->
            var rich: Pair<String, List<Mention>>? = null; var plain: String? = null
            val target = TalkTarget("Message", send = { plain = it; ActionResult() }, sendRich = { t, m -> rich = t to m; ActionResult() })
            val screen = DictationScreen(ui, target); ui.push(screen)
            screen.event(DictationEvent.Done("@Taylor are you in?", listOf(Mention("@Taylor", "@d_1:x", "Taylor"))))
            screen.event(DictationEvent.Done("@Sam Rivera bring cups.", listOf(Mention("@Sam Rivera", "@d_2:x", "Sam Rivera"))))
            screen.key("sel")
            assertNull(plain)
            assertEquals("@Taylor are you in? @Sam Rivera bring cups.", rich!!.first)
            assertEquals(listOf("@d_1:x", "@d_2:x"), rich!!.second.map { it.id })
        }
    }

    @Test fun previewWithoutMentionsUsesPlainSend() {
        fixture { ui, _, _, _ ->
            var rich = false; var plain: String? = null
            val screen = DictationScreen(ui, TalkTarget("Message", send = { plain = it; ActionResult() }, sendRich = { _, _ -> rich = true; ActionResult() }))
            ui.push(screen)
            screen.event(DictationEvent.Done("No tags here."))
            screen.key("sel")
            assertFalse(rich); assertEquals("No tags here.", plain)
        }
    }

    @Test fun mentionMarksFindMultiWordTokensAcrossLinesAndIgnoreLookalikes() {
        val lines = listOf(
            listOf(Token(w = "@Taylor,"), Token(w = "are", gap = true), Token(w = "you", gap = true), Token(w = "@Sam", gap = true)),
            listOf(Token(w = "Rivera?"), Token(w = "@Taylorson", gap = true), Token(w = "@Sam", gap = true), Token(w = "Rivers", gap = true)),
        )
        val marks = mentionMarks(lines, listOf("@Taylor", "@Sam Rivera"))
        assertEquals(setOf(0, 3), marks[0]); assertEquals(setOf(0), marks[1])
        assertTrue(mentionMarks(lines, emptyList()).isEmpty())
    }

    @Test fun controllerWrapperForwardsDictationContext() {
        val owner = Robolectric.buildService(GlassesService::class.java).create()
        try {
            val runtime = owner.get().controller!!
            val inner = Audio()
            runtime.dictation(inner).start("beeper", mapOf("chatId" to "c1")) {}
            assertEquals("beeper" to mapOf("chatId" to "c1"), inner.starts.single())
        } finally { owner.destroy() }
    }
}
