package io.lampyr.glasses.glasses.raster

import io.lampyr.glasses.debug.Latency
import io.lampyr.glasses.render.RasterFit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Requirement 1.4 (latency) transport pieces: pipelining and its fallback, fast reopen, nudge, policy, instrumentation. */
class RasterLatencyTest {
    /** A receiver that keeps its own counters like the firmware and can hold replies back to prove pipelining. */
    private open class Firmware(val session: Long) : RasterWire {
        val ops = mutableListOf<Int>()
        val replies = Channel<ByteArray>(Channel.UNLIMITED)
        /** Requests seen but not yet answered (only used when [holdUntilCommit]). */
        val held = mutableListOf<Tvp2Codec.Packet>()
        var holdUntilCommit = false
        /** Hold every reply until [release] (look-ahead tests). */
        var holdAll = false
        /** Like the firmware, a COMMIT that is not OK does not count as displayed. */
        var countRefused = true
        suspend fun release() { held.forEach { replies.send(reply(it)) }; held.clear() }
        var result: (Tvp2Codec.Packet) -> Int = { 0 }
        var answer = true
        var helloBusy = 0
        private var frames = 0L; private var total = 0L; private var strip = 0L; private var rows = 0L
        override fun reserveSequences(count: Int) = 1
        override suspend fun awaitReply(business: Int, timeoutMs: Long, matches: (ByteArray) -> Boolean): ByteArray = withTimeout(timeoutMs) {
            var found: ByteArray? = null
            while (found == null) { val r = replies.receive(); if (matches(r)) found = r }
            found
        }
        fun reply(q: Tvp2Codec.Packet): ByteArray {
            var code = result(q)
            val p = when (q.op) {
                1 -> if (helloBusy-- > 0) { code = 2; q } else q.copy(a = 540, b = 220L or (1L shl 16), c = 99)
                6 -> { strip = 0; rows = 0; q.copy(a = q.a, b = 0, c = 0) }
                7 -> { strip += q.data.size; rows += 55; q.copy(a = rows, c = strip) }
                8 -> { if (code == 0 || countRefused) { frames++; total += strip }; q.copy(a = frames, c = total) }
                4 -> q.copy(a = 0, b = frames, c = total)
                else -> q
            }
            return Tvp2Codec.encode(p.copy(result = code, data = byteArrayOf()), reply = true)
        }
        override suspend fun send(business: Int, payload: ByteArray, ble: Boolean, sequence: Int?): Int {
            val q = Tvp2Codec.decode(payload, reply = false)!!
            ops += q.op
            if (!answer) return 1
            if (holdAll) held += q
            else if (holdUntilCommit) {
                held += q
                if (q.op == 8) { held.forEach { replies.send(reply(it)) }; held.clear() }
            } else replies.send(reply(q))
            return 1
        }
    }
    private val image = RasterFit.Image(540, 220, 16, ByteArray(118800) { if (it % 7 == 0) 255.toByte() else 0 })

    @After fun clean() { Latency.reset() }

    @Test fun pipelinedFrameWritesAllSixPacketsBeforeAnyReplyAndChecksCounters() = runTest {
        val wire = Firmware(3)
        val sender = Tvp2Transport(wire, 3); sender.open()
        wire.holdUntilCommit = true // The receiver answers nothing until COMMIT arrives: stop-and-wait would deadlock.
        sender.pipeline = true
        var timing: RasterFrameTiming? = null
        sender.onFrameTiming = { timing = it }
        assertArrayEquals(image.pixels, sender.show(image).pixels)
        assertEquals(listOf(1, 2, 6, 7, 7, 7, 7, 8), wire.ops)
        assertTrue(timing!!.pipelined && timing!!.sent && timing!!.bytes > 0)
        // Counters carry over to the next frame and to PING, exactly as in stop-and-wait.
        sender.show(image.copy(pixels = image.pixels.copyOf().apply { this[5] = 255.toByte() }))
        wire.holdUntilCommit = false
        sender.keepAlive()
        // An identical frame is not transmitted and says so.
        val before = wire.ops.size
        sender.show(image.copy(pixels = image.pixels.copyOf().apply { this[5] = 255.toByte() }))
        assertEquals(before, wire.ops.size)
        assertFalse(timing!!.sent)
    }

    @Test fun pipelinedRefusalBusyAndTimeoutAreDistinguished() = runTest {
        // Non-BUSY refusal: the caller must fall back to stop-and-wait.
        val refusing = Firmware(4).apply { result = { if (it.op == 7) 3 else 0 } }
        Tvp2Transport(refusing, 4).apply { open(); pipeline = true }.let { s ->
            try { s.show(image); fail("refused") } catch (_: RasterPipelineRefused) { }
        }
        // BUSY keeps the session: the frame is replaced by the newest one.
        val busy = Firmware(5).apply { result = { if (it.op == 7) 2 else 0 } }
        Tvp2Transport(busy, 5).apply { open(); pipeline = true }.let { s ->
            try { s.show(image); fail("busy") } catch (_: RasterFrameBusy) { }
        }
        // Lost session (result 5) is its own signal: reopen, never fall back to text.
        val lost = Firmware(6).apply { result = { if (it.op == 6) 5 else 0 } }
        Tvp2Transport(lost, 6).apply { open() }.let { s ->
            try { s.show(image); fail("lost") } catch (_: RasterSessionLost) { }
        }
        // No replies at all to a pipelined frame: timeout becomes a pipeline refusal, not a raster failure.
        val silent = Firmware(7)
        Tvp2Transport(silent, 7).apply { open(); pipeline = true }.let { s ->
            silent.answer = false
            try { s.show(image); fail("timeout") } catch (e: RasterPipelineRefused) { assertTrue(e.message!!.contains("timed out")) }
        }
    }

    @Test fun fastReopenRetriesBusyHelloEvery40msAndNudgeDoesNotWait() = runTest {
        val wire = Firmware(8).apply { helloBusy = 3 }
        val sender = Tvp2Transport(wire, 8).apply { busyRetryMs = 40; busyRetries = 25 }
        val start = testScheduler.currentTime
        sender.open()
        assertEquals(120, testScheduler.currentTime - start)
        wire.answer = false
        val before = wire.ops.size
        sender.nudge() // Must return without a reply.
        assertEquals(listOf(4), wire.ops.drop(before))
    }

    @Test fun framePolicyDefersOnlyIdleFramesAndKeepsTheOldestPress() {
        val now = 10_000L
        assertEquals(now, FramePolicy.readyAt(Latency.Kind.INPUT, now, now - 10, now - 10))
        assertEquals(now, FramePolicy.readyAt(Latency.Kind.CHANGE, now, now - 10, now - 10))
        assertEquals(now, FramePolicy.readyAt(null, now, now, now))
        // Idle animation waits 1.5 s after the last press, then runs at one frame per second.
        assertEquals(now - 100 + 1_500, FramePolicy.readyAt(Latency.Kind.IDLE, now, now - 100, 0))
        assertEquals(now + 600, FramePolicy.readyAt(Latency.Kind.IDLE, now, 0, now - 400))
        assertEquals(now, FramePolicy.readyAt(Latency.Kind.IDLE, now, 0, now - 5_000))
        val press = Latency.Meta(Latency.Kind.INPUT, "down", 100, 110, 120)
        val idle = Latency.Meta(Latency.Kind.IDLE, null, null, 200, 210)
        val merged = FramePolicy.merge(press, idle)!!
        assertEquals(Latency.Kind.INPUT, merged.kind); assertEquals(100L, merged.inputAt); assertEquals(210L, merged.renderEnd)
        assertEquals(100L, FramePolicy.merge(press, press.copy(inputAt = 150))!!.inputAt)
        assertEquals(Latency.Kind.INPUT, FramePolicy.merge(press, null)!!.kind)
        assertSame(idle, FramePolicy.merge(null, idle))
    }

    @Test fun latencyTagsFramesByIdentityAndSummarisesTheLast50Presses() {
        var now = 1_000L
        Latency.clock = { now }
        try {
            Latency.input("down"); now += 5; Latency.input("up") // Oldest unrendered press is measured.
            assertTrue(Latency.inputPending)
            val frame = Any()
            val ticket = Latency.renderStarted(dirty = true); now += 15
            Latency.rendered(frame, ticket)
            assertFalse(Latency.inputPending)
            val meta = Latency.metaFor(frame)!!
            assertEquals(Latency.Kind.INPUT, meta.kind); assertEquals("down", meta.key); assertEquals(1_000L, meta.inputAt)
            assertNull(Latency.metaFor(Any()))
            assertEquals(Latency.Kind.IDLE, Latency.renderStarted(dirty = false).kind)
            assertEquals(Latency.Kind.CHANGE, Latency.renderStarted(dirty = true).kind)
            val sample = Latency.committed(meta, beginTx = 1_030, commitRx = 1_280, bytes = 12_000, pipelined = true, behindFrame = false)!!
            assertEquals(280L, sample.totalMs); assertEquals(20L, sample.renderMs); assertEquals(10L, sample.queueMs); assertEquals(250L, sample.wireMs)
            assertNull(Latency.committed(meta.copy(inputAt = null, kind = Latency.Kind.IDLE), 0, 1, 1, false, false))
            repeat(60) { i -> Latency.committed(meta, 1_030, 1_100L + i, 1, false, false) }
            assertEquals(Latency.WINDOW, Latency.recent().size)
            val stat = Latency.stat(Latency.recent().map { it.totalMs })!!
            assertEquals(135L, stat.median); assertEquals(154L, stat.p90)
            assertNull(Latency.stat(emptyList()))
            // A press that rendered nothing is not attached to a frame rendered seconds later.
            Latency.input("x"); now += Latency.STALE_INPUT_MS + 1
            assertEquals(Latency.Kind.CHANGE, Latency.renderStarted(dirty = true).kind)
            assertFalse(Latency.inputPending)
        } finally { Latency.clock = { System.nanoTime() / 1_000_000 } }
    }

    private val second = image.copy(pixels = image.pixels.copyOf().apply { this[9] = 255.toByte() })

    @Test fun aReadyPressFrameIsWrittenBehindTheFrameInFlightAndCollectedLater() = runTest {
        val wire = Firmware(4)
        val sender = Tvp2Transport(wire, 4); sender.open()
        sender.pipeline = true
        wire.holdAll = true
        var ready: Tvp2Transport.Ahead? = Tvp2Transport.Ahead(second, Tvp2Codec.frame(second))
        sender.nextUrgent = { ready.also { ready = null } }
        val timings = mutableListOf<RasterFrameTiming>()
        sender.onFrameTiming = { timings += it }
        val first = async { sender.show(image, Tvp2Codec.frame(image)) }
        runCurrent()
        assertEquals("both bursts are on the wire before any reply", listOf(1, 2) + List(2) { listOf(6, 7, 7, 7, 7, 8) }.flatten(), wire.ops)
        wire.release(); wire.holdAll = false
        first.await()
        val written = wire.ops.size
        assertArrayEquals(second.pixels, sender.show(second, null).pixels)
        assertEquals("the second frame was only collected", written, wire.ops.size)
        assertEquals(2, timings.count { it.sent && it.pipelined })
        sender.keepAlive() // PING counters agree: both frames counted, in order.
    }

    @Test fun whenTheFrameInFrontIsRefusedTheFrameWrittenBehindIsStillAccountedFor() = runTest {
        val wire = Firmware(5)
        val sender = Tvp2Transport(wire, 5); sender.open()
        sender.pipeline = true
        wire.holdAll = true; wire.countRefused = false
        val firstSequences = 3L..8L // HELLO=1, OPEN=2, then the first burst.
        wire.result = { if (it.sequence in firstSequences) 2 else 0 } // BUSY: the first frame is deferred.
        var ready: Tvp2Transport.Ahead? = Tvp2Transport.Ahead(second, Tvp2Codec.frame(second))
        sender.nextUrgent = { ready.also { ready = null } }
        val first = async { runCatching { sender.show(image, Tvp2Codec.frame(image)) } }
        runCurrent()
        wire.release(); wire.holdAll = false
        assertTrue(first.await().exceptionOrNull() is RasterFrameBusy)
        sender.keepAlive() // Counters include the second frame, which the firmware displayed.
        val before = wire.ops.size
        sender.show(second)
        assertEquals("already displayed, so not sent again", before, wire.ops.size)
    }
}
