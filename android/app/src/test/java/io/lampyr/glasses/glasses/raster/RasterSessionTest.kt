package io.lampyr.glasses.glasses.raster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import io.lampyr.glasses.ambient.AmbientRuntime
import io.lampyr.glasses.debug.Latency
import io.lampyr.glasses.glasses.*
import io.lampyr.glasses.glasses.protocol.*
import io.lampyr.glasses.render.RasterFit
import io.lampyr.glasses.render.TextGrid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Requirements 1.3 (stock home flashes) and 1.4 (latency), raster transport side, through the real
 * RayNeoTransport reducer: fast renewal, no caption page when raster is expected, PAUSE recovery,
 * BLACK/PARK display power, pipelining with automatic stop-and-wait fallback, and idle-frame deferral.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RasterSessionTest {
    /** A TVP2 receiver with the firmware's counters, plus the stock bits the transport talks to. */
    private class FirmwareLink(val now: () -> Long) : TransportLink {
        override val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val lastError = MutableStateFlow<String?>(null)
        override val frames = MutableSharedFlow<RayneoFrame>(extraBufferCapacity = 512)
        data class Write(val at: Long, val frame: RayneoFrame) {
            val tvp get() = if (frame.business == 15) Tvp2Codec.decode(frame.payload, reply = false) else null
            val envelope get() = if (tvp == null) runCatching { Envelope.decode(frame.payload) }.getOrNull() else null
        }
        val writes = mutableListOf<Write>()
        var answerHello = true
        /** Result code for a request (0 = OK). */
        var result: (Tvp2Codec.Packet) -> Int = { 0 }
        var silent: (Tvp2Codec.Packet) -> Boolean = { false }
        var session = 0L; var paused = false; private var bad = false
        private var frames0 = 0L; private var total = 0L; private var strip = 0L; private var rows = 0L
        var commits = mutableListOf<Long>() // CRC of every committed frame
        override fun connect(address: String) { connectionState.value = ConnectionState.CONNECTED }
        override fun disconnect() { connectionState.value = ConnectionState.DISCONNECTED }
        override fun retryIfIdle() {}
        override fun close() { disconnect() }
        suspend fun event(business: Int, type: Int, body: JsonObject) { frames.emit(RayneoFrame(business, 1, Envelope(type, body.toString()).encode())) }
        suspend fun pause() {
            paused = true
            frames.emit(RayneoFrame(15, 1, Tvp2Codec.encode(Tvp2Codec.Packet(32, session, 1_000, a = 1), reply = true)))
        }
        override suspend fun send(frame: RayneoFrame) {
            val w = Write(now(), frame); writes += w
            val q = w.tvp
            if (q == null) {
                if (frame.business == 9 && Envelope.decode(frame.payload).type == 1) event(9, 1, obj("OsVersion" to "R1"))
                return
            }
            if (q.op == 1 && !answerHello) return
            if (silent(q)) return
            var code = result(q)
            if (q.op == 6) bad = false
            if (code != 0 && q.op == 7) bad = true
            val p = when (q.op) {
                1 -> q.copy(a = 540, b = 220L or (1L shl 16), c = 77)
                2 -> { session = q.session; paused = false; frames0 = 0; total = 0; q }
                6 -> { strip = 0; rows = 0; q.copy(b = 0, c = 0) }
                7 -> { strip += q.data.size; rows += 55; q.copy(a = rows, c = strip) }
                // A frame with a refused or lost strip never commits (rows short or a strip refused).
                8 -> if (rows != 220L || bad) { code = 1; q } else { frames0++; total += strip; commits += q.c; q.copy(a = frames0, c = total) }
                4 -> q.copy(a = if (paused) 1 else 0, b = frames0, c = total)
                else -> q
            }
            if (q.op != 1 && q.op != 2 && q.session != session) code = 5
            frames.emit(frame.copy(payload = Tvp2Codec.encode(p.copy(result = code, data = byteArrayOf()), reply = true)))
        }
        override suspend fun sendBle(frame: RayneoFrame) { writes += Write(now(), frame) }
        fun ops(from: Int = 0) = writes.drop(from).mapNotNull { it.tvp?.op }
        fun textOpens() = writes.filter { it.frame.business == 0x13 && it.envelope?.type in setOf(1, 7) }
        fun lifeLogSwitches(from: Int = 0) = writes.drop(from).count { it.envelope?.json?.contains("life_log_switch") == true }
        fun lifeLogStops(from: Int = 0) = writes.drop(from).count { it.frame.business == 0x0d && it.envelope?.type == 166 }
    }
    private class Rig(val test: TestScope, mode: String = "AUTO", setup: (android.content.SharedPreferences.Editor) -> Unit = {}) {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("glasses", Context.MODE_PRIVATE)
        val link = FirmwareLink { test.testScheduler.currentTime }
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport: RayNeoTransport
        val selections = mutableListOf<RasterKind?>()
        init {
            if (System.getenv("RASTER_TRACE") != null) io.lampyr.glasses.debug.Trace.sink = { println("TRACE ${test.testScheduler.currentTime} ${it.cat} ${it.msg.take(160)}") }
            prefs.edit().clear().putString("display.mode", mode).putString("glasses.address", "device-a").also(setup).commit()
            transport = RayNeoTransport(context, link, scope, rasterCompute = dispatcher) { test.testScheduler.currentTime }
            scope.launch { transport.raster.selected.collect { selections += it } }
            test.runCurrent()
        }
        fun connect() { link.connect("device-a"); test.runCurrent() }
        fun advance(ms: Long) { test.advanceTimeBy(ms); test.runCurrent() }
        fun close() { transport.close(); test.runCurrent() }
        fun show(color: Int): Frame {
            val bitmap = Bitmap.createBitmap(540, 220, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK); for (x in 0 until 200) setPixel(x, 10, color) }
            val frame = Frame(ByteArray(0), bitmap, TextGrid())
            test.launch { transport.show(frame) }; test.runCurrent()
            return frame
        }
        fun ambient(mode: String) = AmbientRuntime(context).also {
            it.preferences.mode = mode; it.preferences.enabled = true; it.refresh()
            transport.attachAmbient(it); test.runCurrent()
        }
    }
    private val blackCrc = Tvp2Codec.crc(Tvp2Codec.frame(RasterFit.Image(540, 220, 16, ByteArray(540 * 220))).image.pixels)

    @After fun clean() { Latency.reset() }

    @Test fun provenRasterGlassesNeverOpenTheCaptionPageOnConnectEvenWhenHelloIsMissed() = runTest {
        val r = Rig(this) { it.putString("raster.proven.device-a", "TVP2") }
        try {
            r.link.answerHello = false
            r.connect(); r.advance(900)
            assertTrue(r.transport.raster.expectsRaster)
            assertTrue(r.link.textOpens().isEmpty())
            r.link.answerHello = true // First HELLO lost; the retry after 1 s must find TVP2.
            r.advance(3_000)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
            assertTrue("no stock caption page while raster is expected", r.link.textOpens().isEmpty())
        } finally { r.close() }
    }

    @Test fun unprovenAutoStillOpensTextFirstAndRecordsProofForNextTime() = runTest {
        val r = Rig(this)
        try {
            r.connect()
            assertEquals(1, r.link.textOpens().size) // Unknown glasses keep today's text-first behaviour.
            r.advance(2_500)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
            assertEquals("TVP2", r.prefs.getString("raster.proven.device-a", null))
            r.link.disconnect(); runCurrent(); r.connect(); r.advance(2_000)
            assertEquals("reconnect to proven glasses opens no caption page", 1, r.link.textOpens().size)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
        } finally { r.close() }
    }

    @Test fun renewalIsAFastSameBackendReopenThatKeepsLifeLogArmed() = runTest {
        val r = Rig(this, "RASTER")
        val runtime = r.ambient("lifelog")
        try {
            r.connect(); r.advance(2_000)
            r.show(Color.WHITE); r.advance(500)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
            assertTrue("LifeLog armed after raster open", r.link.lifeLogSwitches() >= 1)
            val mark = r.link.writes.size
            val selectionsBefore = r.selections.size
            r.advance(540_000)
            val after = r.link.writes.drop(mark)
            val close = after.indexOfFirst { it.tvp?.op == 5 }
            assertTrue("renewal closes the session", close >= 0)
            val hello = after.drop(close).first { it.tvp?.op == 1 }
            val open = after.drop(close).first { it.tvp?.op == 2 }
            val begin = after.drop(close).first { it.tvp?.op == 6 }
            assertTrue("HELLO right after the CLOSE ack, no 500 ms settle", hello.at - after[close].at < 50)
            assertTrue(open.at - after[close].at < 100)
            assertTrue("cached frame resent at once", begin.at - open.at < 100)
            assertEquals(0, r.link.lifeLogSwitches(mark)); assertEquals(0, r.link.lifeLogStops(mark))
            assertTrue(r.link.textOpens().isEmpty())
            assertFalse("selection never drops to text during renewal", r.selections.drop(selectionsBefore).contains(null))
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
        } finally { r.close(); runtime.close() }
    }

    @Test fun anOpenRefusedWhileLifeLogIsArmedFallsBackToDisarmingFirstAndKeepsTheDisplay() = runTest {
        val r = Rig(this, "RASTER")
        val runtime = r.ambient("lifelog")
        fun armed() = r.link.writes.lastOrNull { it.envelope?.json?.contains("life_log_switch") == true }
            ?.envelope?.json?.replace(" ", "")?.contains("\"value\":1") == true
        try {
            r.connect(); r.advance(2_000)
            r.show(Color.WHITE); r.advance(500)
            assertTrue("LifeLog armed after raster open", armed())
            // Firmware without the gate patch: OPEN is refused while LifeLog is armed.
            r.link.result = { if (it.op == 2 && armed()) 4 else 0 }
            val commits = r.link.commits.size
            r.advance(540_000) // Renewal: the fast reopen is refused once.
            r.advance(3_000)
            assertTrue(r.transport.raster.lifeLogBlocksOpen)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
            assertTrue("frame resent after the fallback open", r.link.commits.size > commits)
            assertTrue("LifeLog re-armed afterwards", armed())
            assertTrue(r.link.textOpens().isEmpty())
            // The next renewal takes the disarm-first path straight away: no refusal.
            val failuresBefore = r.link.writes.count { it.tvp?.op == 2 }
            r.advance(540_000); r.advance(1_000)
            val opens = r.link.writes.drop(0).count { it.tvp?.op == 2 } - failuresBefore
            assertEquals(1, opens)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
            assertTrue(armed())
        } finally { r.close(); runtime.close() }
    }

    @Test fun crownPauseIsRecoveredByAFastReopenAndTheFrameIsResent() = runTest {
        val r = Rig(this, "RASTER")
        try {
            r.connect(); r.advance(2_000)
            r.show(Color.WHITE); r.advance(300)
            val commits = r.link.commits.size
            val mark = r.link.writes.size
            r.link.pause(); runCurrent(); r.advance(300)
            assertEquals(listOf(5, 1, 2, 6, 7, 7, 7, 7, 8), r.link.ops(mark).filter { it != 4 })
            assertEquals(commits + 1, r.link.commits.size)
            assertFalse(r.link.paused)
            // The display keeps working afterwards.
            r.show(Color.GRAY); r.advance(300)
            assertEquals(commits + 2, r.link.commits.size)
            assertTrue(r.link.textOpens().isEmpty())
        } finally { r.close() }
    }

    @Test fun blackBackendSendsOneBlackFrameStaysQuietAndRenewsWhileDarkAfter300s() = runTest {
        val r = Rig(this, "RASTER")
        try {
            r.connect(); r.advance(2_000)
            r.show(Color.WHITE); r.advance(300)
            r.transport.displayAwake(false); r.advance(300)
            assertEquals(blackCrc, r.link.commits.last())
            val commits = r.link.commits.size
            r.show(Color.GRAY); r.advance(5_000)
            assertEquals("no frames while asleep", commits, r.link.commits.size)
            val mark = r.link.writes.size
            r.advance(300_000)
            assertTrue("session renewed while nobody can see it", r.link.ops(mark).containsAll(listOf(5, 1, 2)))
            assertTrue(r.link.textOpens().isEmpty())
            r.transport.displayAwake(true); r.show(Color.WHITE); r.advance(300)
            assertNotEquals(blackCrc, r.link.commits.last())
        } finally { r.close() }
    }

    @Test fun parkBackendClosesThePageWhileDarkAndAStockScreenOnSignalWakesAndReopens() = runTest {
        val r = Rig(this, "RASTER") { it.putString("power.backend", "PARK") }
        val wakes = mutableListOf<String>()
        r.transport.onWearerWake = { wakes += it }
        try {
            r.connect(); r.advance(2_000)
            r.show(Color.WHITE); r.advance(300)
            assertEquals(PowerBackend.PARK, r.transport.raster.powerBackend)
            r.transport.displayAwake(false); r.advance(300)
            assertEquals(blackCrc, r.link.commits.last())
            val mark = r.link.writes.size
            r.advance(2_000)
            assertTrue(r.transport.raster.parked)
            assertEquals(listOf(5), r.link.ops(mark))
            assertEquals("selection kept, so no stock text page opens", RasterKind.TVP2, r.transport.raster.selected.value)
            r.advance(30_000)
            assertEquals("nothing is sent while parked", listOf(5), r.link.ops(mark))
            assertTrue(r.link.textOpens().isEmpty())
            r.link.event(0x0f, 3, obj("cmd" to "screen_status", "payload" to obj("value" to 1))); runCurrent(); r.advance(200)
            assertEquals(listOf("screen_status"), wakes)
            assertTrue("reopened dark at once", r.link.ops(mark).containsAll(listOf(1, 2)))
            r.transport.displayAwake(true); r.show(Color.GRAY); r.advance(400)
            assertFalse(r.transport.raster.parked)
            assertNotEquals(blackCrc, r.link.commits.last())
            // A button wake (no stock signal) unparks too.
            r.transport.displayAwake(false); r.advance(3_000)
            assertTrue(r.transport.raster.parked)
            r.transport.displayAwake(true); r.show(Color.WHITE); r.advance(400)
            assertFalse(r.transport.raster.parked)
            assertNotEquals(blackCrc, r.link.commits.last())
        } finally { r.close() }
    }

    @Test fun aFirmwareRefusalFallsBackToStopAndWaitAndOnlyRepeatedRefusalsPersist() = runTest {
        val r = Rig(this, "RASTER")
        try {
            r.connect(); r.advance(2_000)
            assertTrue(r.transport.raster.pipelineEnabled)
            r.show(Color.WHITE); r.advance(300)
            assertEquals(1, r.link.commits.size)
            var refuse = true
            r.link.result = { if (it.op == 7 && refuse) { refuse = false; 3 } else 0 }
            r.show(Color.GRAY); r.advance(500)
            assertFalse("one refusal is not persisted", r.prefs.contains("raster.pipeline.fallback"))
            assertFalse(r.transport.raster.pipelineEnabled)
            assertEquals("frame resent after a fast reopen", 2, r.link.commits.size)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
            assertTrue(r.link.textOpens().isEmpty())
            r.advance(121_000)
            assertTrue("pipelining is tried again on the same connection", r.transport.raster.pipelineEnabled)
            // Two more refusals in a row, with no pipelined success in between, persist the fallback.
            repeat(2) { n ->
                refuse = true
                r.show(if (n == 0) Color.WHITE else Color.DKGRAY); r.advance(500)
                if (n == 0) r.advance(121_000)
            }
            assertTrue(r.prefs.contains("raster.pipeline.fallback"))
            assertFalse(r.transport.raster.pipelineEnabled)
            // Setting the pref again re-enables pipelining.
            r.prefs.edit().putBoolean("raster.pipeline", true).commit(); runCurrent()
            assertTrue(r.transport.raster.pipelineEnabled)
        } finally { r.close() }
    }

    @Test fun aPipelinedSuccessClearsEarlierFailures() = runTest {
        val r = Rig(this, "RASTER")
        try {
            r.connect(); r.advance(2_000)
            var refuse = true
            r.link.result = { if (it.op == 7 && refuse) { refuse = false; 3 } else 0 }
            r.show(Color.GRAY); r.advance(500)
            assertEquals(1L, r.prefs.getLong("raster.pipeline.timeouts", 0))
            r.advance(121_000)
            r.show(Color.WHITE); r.advance(500)
            assertFalse(r.prefs.contains("raster.pipeline.timeouts"))
            assertTrue(r.transport.raster.pipelineEnabled)
        } finally { r.close() }
    }

    @Test fun aPipelineTimeoutOnlyDisablesPipeliningForThisConnection() = runTest {
        val r = Rig(this, "RASTER")
        try {
            r.connect(); r.advance(2_000)
            var drop = true
            // The link goes quiet from the first strip to the COMMIT of that burst (as if it dropped), then recovers.
            r.link.silent = { if (drop && it.op in 7..8) { if (it.op == 8) drop = false; true } else false }
            r.show(Color.WHITE); r.advance(4_000)
            assertFalse(r.prefs.contains("raster.pipeline.fallback"))
            assertEquals(1L, r.prefs.getLong("raster.pipeline.timeouts", 0))
            assertFalse(r.transport.raster.pipelineEnabled)
            assertEquals(1, r.link.commits.size)
            r.link.disconnect(); runCurrent(); r.connect(); r.advance(2_000)
            assertTrue("a new connection tries pipelining again", r.transport.raster.pipelineEnabled)
        } finally { r.close() }
    }

    @Test fun idleAnimationFramesNeverDelayAPressAndWaitAfterInput() = runTest {
        val r = Rig(this, "RASTER")
        try {
            r.connect(); r.advance(2_000)
            fun tagged(color: Int, dirty: Boolean, key: String? = null): Frame {
                key?.let { Latency.input(it) }
                val ticket = Latency.renderStarted(dirty)
                val bitmap = Bitmap.createBitmap(540, 220, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK); for (x in 0 until 100) setPixel(x, 20, color) }
                val frame = Frame(ByteArray(0), bitmap, TextGrid())
                Latency.rendered(frame, ticket)
                launch { r.transport.show(frame) }; runCurrent()
                return frame
            }
            tagged(Color.WHITE, dirty = true, key = "down"); r.advance(300)
            assertEquals(1, r.link.commits.size)
            r.transport.reclaim(); runCurrent()
            tagged(Color.GRAY, dirty = false) // Animation-only frame right after a press: held back.
            r.advance(1_000)
            assertEquals(1, r.link.commits.size)
            tagged(Color.DKGRAY, dirty = true, key = "up") // The next press replaces it and goes out at once.
            r.advance(300)
            assertEquals(2, r.link.commits.size)
            assertTrue(Latency.recent().map { it.key }.containsAll(listOf("down", "up")))
            tagged(Color.GRAY, dirty = false); r.advance(1_000)
            assertEquals(2, r.link.commits.size)
            r.advance(1_000)
            assertEquals("idle frame sent once the input hold has passed", 3, r.link.commits.size)
        } finally { r.close() }
    }
}
