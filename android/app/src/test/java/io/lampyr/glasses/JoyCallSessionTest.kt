package io.lampyr.glasses

import io.lampyr.glasses.audio.*
import io.lampyr.glasses.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JoyCallSessionTest {
    class Socket : JoyCallSocket, JoyCallSocketFactory {
        lateinit var listener: JoyCallSocket.Listener
        val text = mutableListOf<String>()
        val binary = mutableListOf<ByteArray>()
        var closed = false
        var sendOk = true
        var connects = 0
        val resumes = mutableListOf<String?>()
        override fun connect(listener: JoyCallSocket.Listener): JoyCallSocket { connects++; closed = false; this.listener = listener; return this }
        override fun reconnect(listener: JoyCallSocket.Listener, resume: String?): JoyCallSocket { resumes += resume; return connect(listener) }
        override fun text(value: String): Boolean { text += value; return sendOk }
        override fun binary(value: ByteArray): Boolean { binary += value; return sendOk }
        override fun close() { closed = true }
        fun event(type: String, field: String = "", value: String = "") = listener.text(obj("type" to str(type), field to str(value)).toString())
        fun count(type: String) = text.count { gatewayJson.parseToJsonElement(it).s("type") == type }
    }
    class Audio : JoyCallAudio {
        override val sampleRate = 16_000
        override var playedSamples = 0L
        var pcm: ((ShortArray) -> Unit)? = null
        var interrupt: (() -> Unit)? = null
        var closed = false
        var writes = 0
        var bytes = 0
        var plays = 0
        var flushes = 0
        var maxWrite = 960
        override fun open(interrupted: () -> Unit): Boolean { interrupt = interrupted; return true }
        override fun capture(accept: (ShortArray) -> Unit): Boolean { pcm = accept; return true }
        override fun write(pcm: ByteArray, offset: Int): Int { writes++; val n = minOf(maxWrite, pcm.size - offset); bytes += n; return n }
        override fun play() { plays++ }
        override fun flush() { flushes++; playedSamples = 0 }
        override fun close() { closed = true; pcm = null }
    }
    class Exclusion : JoyCallExclusion {
        val events = mutableListOf<String>()
        override fun begin() { events += "begin" }
        override fun end() { events += "end" }
    }
    private class Rig(scope: TestScope, maxCallMs: Long = JoyCallSession.MAX_CALL_MS) {
        val socket = Socket()
        val audio = Audio()
        val exclusion = Exclusion()
        val states = mutableListOf<String>()
        val workers = mutableListOf<JoyWorker?>()
        var clockOffset = 0L
        val session = JoyCallSession(socket, audio, exclusion, { scope.testScheduler.currentTime + clockOffset }, { states += it },
            worker = { workers += it }, maxCallMs = maxCallMs)
        val result = scope.async { session.run() }
        fun json(raw: String) = socket.listener.text(raw)
    }
    @Test fun exactFramingResamplesAcrossChunkBoundariesAndBoundsLatency() {
        val whole = CallPcmFrames(48_000)
        val chunked = CallPcmFrames(48_000)
        val samples = ShortArray(3840) { (it * 7).toShort() }
        whole.accept(samples)
        samples.toList().chunked(37).forEach { chunked.accept(it.toShortArray()) }
        repeat(4) { assertArrayEquals(whole.take(false), chunked.take(false)) }
        val frames = CallPcmFrames(16_000)
        repeat(20) { i -> frames.accept(ShortArray(320) { i.toShort() }) }
        assertEquals(640, frames.take(false).size)
        val newest = frames.take(false)
        assertEquals(16, newest[0].toInt()) // only the most recent five frames are retained
    }
    @Test fun readyGatesCaptureAndPacesWithoutBurstingAndMuteSendsSilence() = runTest {
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); runCurrent()
        assertEquals(1, r.socket.count("hello")); assertEquals(1, r.socket.count("call_start"))
        assertTrue(r.socket.text.first().contains("rayneo-android"))
        assertTrue(r.socket.text[1].contains("\"preroll_ms\":0"))
        advanceTimeBy(500); runCurrent(); assertNull(r.audio.pcm); assertTrue(r.socket.binary.isEmpty())
        r.socket.event("ready"); runCurrent()
        r.audio.pcm!!(ShortArray(1600) { 1200 }); advanceTimeBy(100); runCurrent()
        assertEquals(6, r.socket.binary.size); assertTrue(r.socket.binary.all { it.size == 640 })
        assertTrue(r.socket.binary.any { frame -> frame.any { it != 0.toByte() } })
        r.session.mute(true); val n = r.socket.binary.size
        r.audio.pcm!!(ShortArray(960) { 2000 }); advanceTimeBy(60); runCurrent()
        assertEquals(n + 3, r.socket.binary.size)
        assertTrue(r.socket.binary.drop(n).all { frame -> frame.all { it == 0.toByte() } })
        // Silence is PCM, never an application heartbeat or fake listening event. Server silence
        // and unanswered hangups remain authoritative even while the user is muted.
        r.socket.event("hangup", "reason", "silence"); runCurrent()
        assertEquals(JoyCallEnd("silence"), r.result.await()); assertTrue(r.audio.closed)
        assertEquals(listOf("begin", "end"), r.exclusion.events)
    }
    @Test fun delayedPacingDropsOldDeadlinesInsteadOfBurstingAudio() = runTest {
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); r.socket.event("ready"); runCurrent()
        assertEquals(1, r.socket.binary.size)
        r.clockOffset = 500
        advanceTimeBy(20); runCurrent(); assertEquals(2, r.socket.binary.size)
        advanceTimeBy(19); runCurrent(); assertEquals(2, r.socket.binary.size)
        advanceTimeBy(1); runCurrent(); assertEquals(3, r.socket.binary.size)
        r.session.close(); runCurrent(); r.result.await()
    }
    @Test fun bridgeStatesMapAndClearImmediatelyFlushesWithoutReportingDiscardedAudioDrained() = runTest {
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); runCurrent()
        r.socket.event("ready"); r.socket.event("state", "value", "thinking")
        r.socket.event("state", "value", "joy_speaking"); r.socket.event("state", "value", "listening"); runCurrent()
        assertEquals(listOf("listening", "thinking", "speaking", "listening"), r.states)
        repeat(4) { r.socket.listener.binary(ByteArray(960)) }; runCurrent(); advanceTimeBy(10); runCurrent()
        assertTrue(r.audio.writes > 0); assertEquals(0, r.socket.count("drained"))
        r.socket.event("clear"); runCurrent(); assertEquals(1, r.audio.flushes)
        r.audio.playedSamples = 9999; advanceTimeBy(100); runCurrent(); assertEquals(0, r.socket.count("drained"))
        r.session.close(); runCurrent(); r.result.await()
    }
    @Test fun jitterBufferAndPartialWritesWaitForPlaybackHeadBeforeDrainedExactlyOnce() = runTest {
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); runCurrent()
        r.audio.maxWrite = 120
        r.socket.listener.binary(ByteArray(960)); runCurrent()
        advanceTimeBy(79); runCurrent(); assertEquals(0, r.audio.writes)
        advanceTimeBy(1); runCurrent(); assertEquals(960, r.audio.bytes)
        assertEquals(1, r.audio.plays); assertEquals(0, r.socket.count("drained"))
        r.audio.playedSamples = 479; advanceTimeBy(20); runCurrent(); assertEquals(0, r.socket.count("drained"))
        r.audio.playedSamples = 480; advanceTimeBy(20); runCurrent(); assertEquals(1, r.socket.count("drained"))
        advanceTimeBy(200); runCurrent(); assertEquals(1, r.socket.count("drained"))
        r.session.close(); runCurrent(); r.result.await()
    }
    @Test fun pingEveryTwentySecondsAndCapEndsCallWithoutRedial() = runTest {
        val r = Rig(this, maxCallMs = 15 * 60_000L); runCurrent(); r.socket.listener.opened(); r.socket.event("ready"); runCurrent()
        advanceTimeBy(19_999); runCurrent(); assertEquals(0, r.socket.count("ping"))
        advanceTimeBy(1); runCurrent(); assertEquals(1, r.socket.count("ping"))
        advanceTimeBy(20_000); runCurrent(); assertEquals(2, r.socket.count("ping"))
        advanceTimeBy(15 * 60_000L - 40_000); runCurrent(); assertEquals("cap", r.result.await().reason)
        assertEquals(1, r.socket.count("call_start")); assertTrue(r.socket.closed)
    }
    @Test fun unavailableAndFailedUpgradeBeforeReadyFallBackWithoutRedial() = runTest {
        for (kind in listOf("upgrade", "unavailable")) {
            val r = Rig(this); runCurrent()
            if (kind != "upgrade") { r.socket.listener.opened(); runCurrent() }
            if (kind == "unavailable") r.socket.event("hangup", "reason", "unavailable") else r.socket.listener.ended()
            runCurrent(); assertTrue(r.result.await().fallback)
            assertEquals(1, r.socket.connects)
            assertEquals(listOf("begin", "end"), r.exclusion.events)
        }
    }
    @Test fun defaultCapIsFourHoursAndAHalfHourCallStaysUp() = runTest {
        assertEquals(4 * 60 * 60_000L, JoyCallSession.MAX_CALL_MS)
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); r.socket.event("ready"); runCurrent()
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertFalse(r.result.isCompleted); assertEquals(90, r.socket.count("ping"))
        r.session.close(); runCurrent(); r.result.await()
    }
    @Test fun midCallDropReconnectsWithResumeKeepsCaptureAndTheExclusion() = runTest {
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); runCurrent()
        assertTrue(r.socket.text.first().contains("\"caps\":[\"agent\",\"resume\",\"session\"]"))
        r.socket.event("ready"); r.socket.event("call", "id", "c-42"); runCurrent()
        val mic = r.audio.pcm!!
        repeat(10) { r.socket.listener.binary(ByteArray(960)) }; runCurrent()
        r.socket.listener.ended("connection lost"); runCurrent()
        assertEquals("reconnecting", r.states.last()); assertTrue(r.socket.closed)
        assertFalse(r.result.isCompleted)
        advanceTimeBy(249); runCurrent(); assertEquals(1, r.socket.connects)
        advanceTimeBy(20); runCurrent(); assertEquals(2, r.socket.connects); assertEquals(listOf<String?>("c-42"), r.socket.resumes)
        val sentBefore = r.socket.binary.size
        r.socket.listener.opened(); runCurrent()
        val start = r.socket.text.last { gatewayJson.parseToJsonElement(it).s("type") == "call_start" }
        assertTrue(start, start.contains("\"resume\":\"c-42\""))
        advanceTimeBy(100); runCurrent(); assertEquals(sentBefore, r.socket.binary.size) // no uplink until ready
        r.socket.event("ready"); runCurrent()
        assertEquals("listening", r.states.last()); assertEquals(1, r.session.reconnects)
        assertSame(mic, r.audio.pcm); assertFalse(r.audio.closed)
        assertEquals(listOf("begin"), r.exclusion.events) // LifeLog stays paused across the reconnect
        advanceTimeBy(100); runCurrent(); assertTrue(r.socket.binary.size > sentBefore)
        r.session.close(); runCurrent(); assertEquals("user", r.result.await().reason)
        assertEquals(listOf("begin", "end"), r.exclusion.events)
    }
    @Test fun recoverableBridgeHangupsReconnectButRealHangupsEnd() = runTest {
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); r.socket.event("ready"); runCurrent()
        r.socket.event("hangup", "reason", "error"); runCurrent()
        assertFalse(r.result.isCompleted); assertEquals("reconnecting", r.states.last())
        advanceTimeBy(300); runCurrent(); assertEquals(2, r.socket.connects); assertEquals(listOf<String?>(null), r.socket.resumes)
        r.socket.listener.opened(); r.socket.event("ready"); runCurrent()
        r.socket.event("hangup", "reason", "signoff"); runCurrent()
        assertEquals(JoyCallEnd("signoff"), r.result.await())
    }
    @Test fun reconnectGivesUpAfterTheWindowAndStaleSocketEventsAreIgnored() = runTest {
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); r.socket.event("ready"); runCurrent()
        val old = r.socket.listener
        r.socket.listener.ended(); runCurrent()
        old.text("""{"type":"hangup","reason":"silence"}"""); runCurrent()
        assertFalse(r.result.isCompleted)
        advanceTimeBy(JoyCallSession.RECONNECT_WINDOW_MS); runCurrent()
        assertEquals(JoyCallEnd("connection lost"), r.result.await())
        assertTrue(r.socket.connects >= 4) // backoff retries, each abandoned after ATTEMPT_TIMEOUT_MS
        assertTrue(r.audio.closed); assertEquals(listOf("begin", "end"), r.exclusion.events)
    }
    @Test fun aPlaybackBurstDropsOldAudioInsteadOfEndingTheCall() = runTest {
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); r.socket.event("ready"); runCurrent()
        r.audio.maxWrite = 0 // the track is full: nothing drains
        repeat(JoyCallSession.DOWNLINK_MAX + 100) { r.socket.listener.binary(ByteArray(960)) }; runCurrent()
        assertFalse(r.result.isCompleted)
        assertTrue(r.session.droppedFrames > 0)
        r.session.close(); runCurrent(); assertEquals("user", r.result.await().reason)
    }
    @Test fun agentSessionAndClearEventsDriveWorkerAndVoiceStates() = runTest {
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); r.socket.event("ready"); runCurrent()
        r.json("""{"type":"agent","state":"started","task":"weather in Springfield"}""")
        r.json("""{"type":"agent","state":"tool","tool":"web_search"}""")
        r.json("""{"type":"agent","state":"idle"}""")
        r.json("""{"type":"session","state":"rotating"}"""); r.json("""{"type":"session","state":"rotated"}""")
        r.socket.event("clear"); runCurrent()
        assertEquals(listOf(JoyWorker("started", task = "weather in Springfield"), JoyWorker("tool", "web_search"), null), r.workers)
        assertEquals(listOf("listening", "compacting", "listening", "interrupted"), r.states)
        r.session.close(); runCurrent(); r.result.await()
    }
    @Test fun userPhoneServiceAndFocusHangupsReleaseEverythingAndCancellationAlsoCloses() = runTest {
        for (reason in listOf("user", "phone call", "service destroyed", "focus", "cancel")) {
            val r = Rig(this); runCurrent(); r.socket.listener.opened(); runCurrent(); r.socket.event("ready"); runCurrent()
            when (reason) {
                "focus" -> r.audio.interrupt!!()
                "cancel" -> r.result.cancel()
                else -> r.session.close(reason)
            }
            runCurrent()
            assertTrue(r.socket.closed); assertTrue(r.audio.closed); assertEquals(listOf("begin", "end"), r.exclusion.events)
            assertEquals(1, r.socket.count("hangup"))
        }
    }
    @Test fun missingReadyTimesOutAndBadAudioEndsCleanly() = runTest {
        val r = Rig(this); runCurrent(); r.socket.listener.opened(); runCurrent()
        advanceTimeBy(12_000); runCurrent(); assertTrue(r.result.await().fallback)
        val bad = Rig(this); runCurrent(); bad.socket.listener.opened(); runCurrent()
        bad.socket.listener.binary(ByteArray(959)); runCurrent()
        assertEquals("invalid audio frame", bad.result.await().reason); assertTrue(bad.audio.closed)
    }
}
