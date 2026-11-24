package io.lampyr.glasses.glasses

import android.content.Context
import io.lampyr.glasses.ambient.*
import io.lampyr.glasses.glasses.protocol.*
import io.lampyr.glasses.glasses.raster.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransportLifecycleTest {
    private class FakeLink : TransportLink {
        override val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val lastError = MutableStateFlow<String?>(null)
        override val frames = MutableSharedFlow<RayneoFrame>(extraBufferCapacity = 128)
        val writes = mutableListOf<RayneoFrame>()
        var firmware = "stock-1"
        var turbo = false
        var glassly = false
        var fail: suspend (RayneoFrame) -> Unit = {}
        var lastTvp: Tvp2Codec.Packet? = null
        override fun connect(address: String) { connectionState.value = ConnectionState.CONNECTED }
        override fun disconnect() { connectionState.value = ConnectionState.DISCONNECTED }
        override fun retryIfIdle() {}
        val wakes = mutableListOf<Pair<String, Boolean>>()
        override fun wake(reason: String, userAction: Boolean) { wakes += reason to userAction }
        override fun close() { disconnect() }
        suspend fun event(business: Int, type: Int, body: kotlinx.serialization.json.JsonObject) {
            frames.emit(RayneoFrame(business, 1, Envelope(type, body.toString()).encode()))
        }
        override suspend fun send(frame: RayneoFrame) {
            writes += frame
            fail(frame)
            val q = if (frame.business == 15) Tvp2Codec.decode(frame.payload, reply = false) else null
            if (q != null) {
                lastTvp = q
                if (turbo) frames.emit(frame.copy(payload = Tvp2Codec.encode(q.copy(
                    a = if (q.op == 1) 540 else 0,
                    // PING echoes the displayed/verified counters like the firmware (nothing committed here: 0).
                    b = if (q.op == 1) 220L or (1L shl 16) else 0, c = if (q.op == 4) 0 else 1), reply = true)))
            } else if (frame.business == 9 && Envelope.decode(frame.payload).type == 1) {
                event(9, 1, obj("OsVersion" to firmware))
            }
        }
        override suspend fun sendBle(frame: RayneoFrame) {
            writes += frame; fail(frame)
            if (glassly && frame.payload.contentEquals(byteArrayOf(0x10, 0, 1))) {
                val ident = byteArrayOf(0x11, 1, 0x1c, 2, 0xdc.toByte(), 0, 0xf0.toByte(), 0, 0, 0, 11, 0, 0x12) + "GLASSLYCFW-IO/11".toByteArray()
                frames.emit(frame.copy(payload = ident))
            } else if (glassly && frame.payload.size == 3 && frame.payload[0] == 0x10.toByte()) {
                val open = frame.payload[2] != 3.toByte()
                frames.emit(frame.copy(payload = byteArrayOf(0x13, if (open) 1 else 0, 0)))
            }
        }
        fun textOpens() = writes.filter { it.business == 0x13 && Envelope.decode(it.payload).type in setOf(1, 7) }
        fun probes() = writes.count { it.business == 0xf0 && it.payload.contentEquals(byteArrayOf(0x10, 0, 1)) || it.business == 15 && Tvp2Codec.decode(it.payload, false)?.op == 1 }
    }
    private class Rig(val test: TestScope, mode: String = "AUTO") {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("glasses", Context.MODE_PRIVATE)
        val link = FakeLink()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val transport: RayNeoTransport
        init {
            prefs.edit().clear().putString("display.mode", mode).putString("glasses.address", "device-a").commit()
            transport = RayNeoTransport(context, link, scope) { test.testScheduler.currentTime }
            test.runCurrent()
        }
        fun connect() { link.connect("device-a"); test.runCurrent() }
        fun advance(ms: Long) { test.advanceTimeBy(ms); test.runCurrent() }
        fun close() { transport.close(); test.runCurrent() }
        fun ambient(mode: String): AmbientRuntime {
            return AmbientRuntime(context).also {
                it.preferences.mode = mode; it.preferences.enabled = true; it.refresh()
                transport.attachAmbient(it); test.runCurrent()
            }
        }
    }
    @Test fun textModeNeverProbesAndAutoOpensTextBeforeProbeCompletes() = runTest {
        for (mode in listOf("TEXT", "AUTO")) {
            val r = Rig(this, mode)
            try {
                r.connect()
                assertEquals(1, r.link.textOpens().size)
                assertEquals(mode == "AUTO", r.transport.raster.probing.value)
                r.advance(3500)
                assertEquals(if (mode == "TEXT") 0 else 2, r.link.probes())
            } finally { r.close() }
        }
    }
    @Test fun reclaimAndEventsWakeTheLinkWithTheirReason() = runTest {
        val r = Rig(this)
        try {
            // Disconnected: a controller key, the assistant or a clock alert retries at once (and may restart a stopped link).
            r.transport.reclaim(); r.transport.reclaim("clock alert"); runCurrent()
            r.transport.wake("phone screen on"); runCurrent()
            assertEquals(listOf("controller key" to true, "clock alert" to true, "phone screen on" to false), r.link.wakes)
        } finally { r.close() }
    }
    @Test fun rasterModeWaitsWithoutOpeningText() = runTest {
        val r = Rig(this, "RASTER")
        try { r.connect(); r.advance(4000); assertTrue(r.link.textOpens().isEmpty()) }
        finally { r.close() }
    }
    @Test fun negativeProbeCacheIsIgnoredAndTimeoutReconnectCanDiscoverRaster() = runTest {
        val r = Rig(this)
        try {
            r.prefs.edit().putString("raster.cache.device-a/stock-1", "{\"turbo\":false}").commit()
            r.connect(); r.advance(3500); assertEquals(2, r.link.probes())
            assertFalse(r.prefs.contains("raster.cache.device-a/stock-1"))
            r.link.disconnect(); runCurrent(); r.link.turbo = true; r.connect(); r.advance(2800)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
            assertTrue(r.link.probes() >= 4)
            val before = r.link.probes()
            r.link.disconnect(); runCurrent(); r.link.firmware = "stock-2"; r.connect(); r.advance(3500)
            assertTrue(r.link.probes() >= before + 2)
            val next = r.link.probes()
            r.link.disconnect(); runCurrent(); r.prefs.edit().putString("glasses.address", "device-b").commit()
            r.connect(); r.advance(3500); assertTrue(r.link.probes() >= next + 2)
            val forced = r.link.probes()
            r.transport.raster.forceTry(RasterKind.TVP2); r.advance(1500)
            assertTrue(r.link.probes() > forced)
        } finally { r.close() }
    }

    @Test fun continuousStartTimeoutRestoresDisplayAndRetriesWithBoundedBackoff() = runTest {
        val r = Rig(this, "TEXT")
        val runtime = r.ambient("continuous")
        var attempts = 0
        r.link.fail = { if (it.business == 0x17 && Envelope.decode(it.payload).type == 1) {
            attempts++
            if (attempts <= 2) withTimeout(5) { delay(10) }
        } }
        try {
            r.connect(); r.advance(50)
            assertEquals(1, attempts)
            assertFalse(r.transport.raster.suspended)
            assertTrue(r.link.textOpens().isNotEmpty())
            r.advance(900); assertEquals(1, attempts)
            r.advance(200); assertEquals(2, attempts)
            assertFalse(r.transport.raster.suspended)
            r.advance(1800); assertEquals(2, attempts)
            r.advance(300); assertEquals(3, attempts)
            r.advance(3000); assertEquals(3, attempts)
            assertTrue(r.transport.raster.suspended)
            assertEquals(ConnectionState.CONNECTED, r.link.connectionState.value)
        } finally { r.close(); runtime.close() }
    }

    @Test fun continuousWearerExitAndReclaimRestoreTextOrRasterAndPauseCapture() = runTest {
        for (turbo in listOf(false, true)) {
            val r = Rig(this); r.link.turbo = turbo
            val runtime = r.ambient("continuous")
            try {
                r.connect(); r.advance(3500)
                val prompt = r.link.writes.last { it.business == 0x17 && Envelope.decode(it.payload).type == 1 }
                val body = kotlinx.serialization.json.Json.parseToJsonElement(Envelope.decode(prompt.payload).json) as kotlinx.serialization.json.JsonObject
                r.link.event(0x17, 3, obj("sid" to body.string("sid"))); runCurrent(); r.advance(500)
                val prompts = r.link.writes.count { it.business == 0x17 && Envelope.decode(it.payload).type == 1 }
                val text = r.link.textOpens().size
                r.transport.reclaim(); runCurrent(); r.advance(1500)
                assertEquals(prompts, r.link.writes.count { it.business == 0x17 && Envelope.decode(it.payload).type == 1 })
                assertFalse(r.transport.raster.suspended)
                if (turbo) assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
                else assertTrue(r.link.textOpens().size > text)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(runtime.status.value.state, runtime.status.value.state.contains("Continuous paused"))
            } finally { r.close(); runtime.close() }
        }
    }

    @Test fun controllerReclaimDuringContinuousBackoffKeepsTheUiDisplay() = runTest {
        val r = Rig(this, "TEXT")
        val runtime = r.ambient("continuous")
        var attempts = 0
        r.link.fail = { if (it.business == 0x17 && Envelope.decode(it.payload).type == 1) {
            attempts++; withTimeout(5) { delay(10) }
        } }
        try {
            r.connect(); r.advance(50); assertEquals(1, attempts)
            r.transport.reclaim(); runCurrent(); r.advance(65_000)
            assertEquals(1, attempts)
            assertFalse(r.transport.raster.suspended)
            assertTrue(r.link.textOpens().isNotEmpty())
        } finally { r.close(); runtime.close() }
    }
    @Test fun reducerSurvivesTimeoutInsideSignalAndContinuesReceivingAndOpeningPages() = runTest {
        val r = Rig(this, "TEXT")
        var fail = true
        r.link.fail = { frame -> if (fail && frame.business == 0x13) {
            fail = false; withTimeout(5) { delay(10) }
        } }
        try {
            r.connect(); r.advance(10)
            assertNotNull(r.transport.lastError.value)
            r.link.event(9, 1, obj("BatteryLevel" to 73)); runCurrent()
            assertEquals(73, r.transport.deviceStatusFlow.value.battery)
            r.link.disconnect(); runCurrent(); r.connect()
            assertEquals(2, r.link.textOpens().size)
        } finally { r.close() }
    }
    @Test fun glasslyProbeBleTimeoutDoesNotKillRasterWorker() = runTest {
        val r = Rig(this)
        r.link.fail = { if (it.business == 0xf0) withTimeout(5) { delay(10) } }
        try {
            r.connect(); r.advance(1100)
            assertTrue(r.transport.raster.ready.value)
            assertFalse(r.transport.raster.probing.value)
            r.link.fail = {}; r.link.turbo = true
            r.transport.raster.forceTry(RasterKind.TVP2); r.advance(800)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
        } finally { r.close() }
    }
    @Test fun micStopWithRasterDoesNotFlashTextAndDropStopSignalCannotReopenDuringProbe() = runTest {
        for (mode in listOf("AUTO", "RASTER")) {
            val r = Rig(this, mode); r.link.turbo = true
            try {
                r.connect(); r.advance(2800)
                assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
                // The microphone is the assistant recorder: raster keeps the display for the whole capture.
                fun recorder(rc: Int) = r.link.writes.count { it.business == 0x0d && Envelope.decode(it.payload).let { e -> e.type == 2 && e.json == "{\"rc\":$rc}" } }
                val count = r.link.textOpens().size
                var pcm = 0
                assertTrue(r.transport.mic.start { pcm += it.size })
                runCurrent(); r.advance(200)
                assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
                assertEquals(1, recorder(1)); assertEquals(0, recorder(2))
                val before = r.transport.mic.packets.get()
                r.link.frames.emit(RayneoFrame(0x0d, 7, Envelope(3, "", ByteArray(240) { 8 }).encode())); runCurrent()
                assertEquals(before + 1, r.transport.mic.packets.get())
                r.transport.mic.stop(); runCurrent(); r.advance(800)
                assertEquals(1, recorder(2))
                assertEquals(count, r.link.textOpens().size)
                assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
                r.transport.mic.start {}; runCurrent(); r.advance(200)
                r.link.disconnect(); runCurrent()
                assertFalse(r.transport.mic.isCapturing())
                r.connect(); r.advance(50)
                // Forced raster never opens a stock text page, now that the microphone no longer needs one.
                if (mode == "AUTO") assertEquals(7, Envelope.decode(r.link.textOpens().last().payload).type) else assertTrue(r.link.textOpens().isEmpty())
                val afterReconnect = r.link.textOpens().size
                r.advance(500); assertEquals(afterReconnect, r.link.textOpens().size)
            } finally { r.close() }
        }
    }
    @Test fun textWearerExitHoldsUntilReclaimButRealReconnectResetsHold() = runTest {
        val r = Rig(this, "TEXT")
        try {
            r.connect()
            val sid = kotlinx.serialization.json.Json.parseToJsonElement(Envelope.decode(r.link.textOpens().last().payload).json) as kotlinx.serialization.json.JsonObject
            r.link.event(0x13, 3, obj("sid" to sid.string("sid"))); runCurrent(); r.advance(1000)
            assertEquals(1, r.link.textOpens().size)
            r.transport.reclaim(); runCurrent(); r.advance(25); assertEquals(2, r.link.textOpens().size)
            val second = kotlinx.serialization.json.Json.parseToJsonElement(Envelope.decode(r.link.textOpens().last().payload).json) as kotlinx.serialization.json.JsonObject
            r.link.event(0x13, 3, obj("sid" to second.string("sid"))); runCurrent()
            r.link.disconnect(); runCurrent(); r.connect(); assertEquals(3, r.link.textOpens().size)
        } finally { r.close() }
    }
    @Test fun rasterWearerExitsNeverFallBackToTextUntilReclaim() = runTest {
        for (kind in RasterKind.entries) {
            val r = Rig(this); r.link.turbo = kind == RasterKind.TVP2; r.link.glassly = kind == RasterKind.GLASSLY
            try {
                r.connect(); r.advance(2800)
                assertEquals(kind, r.transport.raster.selected.value)
                val opens = r.link.textOpens().size
                val payload = if (kind == RasterKind.TVP2) Tvp2Codec.encode(r.link.lastTvp!!.copy(op = 33, sequence = 100), reply = true)
                    else byteArrayOf(0x13, 0, 4)
                r.link.frames.emit(RayneoFrame(if (kind == RasterKind.TVP2) 15 else 0xf0, 1, payload))
                runCurrent(); r.advance(500)
                assertEquals(null, r.transport.raster.selected.value)
                assertEquals(opens, r.link.textOpens().size)
                r.transport.reclaim(); runCurrent(); r.advance(800)
                assertEquals(kind, r.transport.raster.selected.value)
            } finally { r.close() }
        }
    }
    @Test fun ambientWaitsForDecisionAndContinuousClosesTextBeforeOpeningPrompt() = runTest {
        val r = Rig(this)
        val runtime = r.ambient("continuous")
        try {
            r.connect(); r.advance(100)
            assertFalse(r.link.writes.any { it.business == 0x17 })
            r.advance(3100)
            val prompt = r.link.writes.indexOfFirst { it.business == 0x17 && Envelope.decode(it.payload).type == 1 }
            val close = r.link.writes.indexOfFirst { it.business == 0x13 && Envelope.decode(it.payload).type == 3 }
            assertTrue(close >= 0 && prompt > close)
            val opens = r.link.textOpens().size
            r.advance(1000); assertEquals(opens, r.link.textOpens().size)
            // A stale page exit must not release the current prompt.
            r.link.event(0x17, 3, obj("sid" to "old")); runCurrent()
            r.advance(100); assertEquals(opens, r.link.textOpens().size)
        } finally { r.close(); runtime.close() }
    }
    @Test fun lifeLogArmsAfterRasterOpenAndCaptionPreemptionClosesPrompt() = runTest {
        val r = Rig(this); r.link.turbo = true
        val runtime = r.ambient("lifelog")
        try {
            r.connect(); r.advance(2800)
            val open = r.link.writes.indexOfFirst { it.business == 15 && Tvp2Codec.decode(it.payload, false)?.op == 2 }
            val arm = r.link.writes.indexOfFirst { it.business == 15 && Tvp2Codec.decode(it.payload, false) == null && Envelope.decode(it.payload).json.contains("life_log_switch") }
            assertTrue(open >= 0 && arm > open)
            runtime.preferences.mode = "continuous"; r.transport.ambientChanged(); runCurrent(); r.advance(200)
            val promptStart = r.link.writes.indexOfLast { it.business == 0x17 && Envelope.decode(it.payload).type == 1 }
            assertTrue(promptStart >= 0)
            r.transport.mic.start {}; runCurrent(); r.advance(200)
            val promptClose = r.link.writes.indexOfLast { it.business == 0x17 && Envelope.decode(it.payload).type == 3 }
            val caption = r.link.writes.indexOfLast { it.business == 0x13 && Envelope.decode(it.payload).type == 1 }
            assertTrue(promptClose > promptStart && caption > promptClose)
        } finally { r.close(); runtime.close() }
    }
    @Test fun switchingFromTextToAutoProbesWithoutReconnect() = runTest {
        val r = Rig(this, "TEXT")
        try {
            r.connect(); assertEquals(0, r.link.probes())
            r.prefs.edit().putString("display.mode", "AUTO").commit()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            runCurrent(); r.advance(3500)
            assertEquals(2, r.link.probes())
        } finally { r.close() }
    }
    @Test fun ambientTriggerIsDeliveredBeforeAutoSwitchAndLogFailureCannotHideEvents() = runTest {
        val r = Rig(this)
        val runtime = r.ambient("auto")
        val observed = mutableListOf<GlassesEvent>()
        var stopped = false
        val spy = object : AmbientCaptureSource {
            override suspend fun start() {}
            override suspend fun stop() { stopped = true }
            override suspend fun event(event: GlassesEvent, utc: Long) { assertFalse(stopped); observed += event }
        }
        fun field(name: String, value: Any) {
            RayNeoTransport::class.java.getDeclaredField(name).apply { isAccessible = true }.set(r.transport, value)
        }
        try {
            r.connect(); r.advance(3500)
            field("ambientSource", spy)
            field("experimentActive", true)
            val log = java.io.File(r.context.filesDir, "display-experiments.log")
            log.delete(); assertTrue(log.mkdir())
            r.link.event(15, 21, obj("cmd" to "life_log_switch_result")); runCurrent()
            assertTrue(observed.single() is GlassesEvent.Message)
            assertTrue(r.transport.lastError.value!!.contains("Experiment log"))
            assertTrue(log.delete())
            field("experimentActive", false)
            // No-wake packets must reach the old source before it is stopped. Sources still validate SID/task IDs.
            r.advance(10_000)
            r.link.frames.emit(RayneoFrame(0x17, 22, Envelope(4, obj("sid" to "unmatched", "seq" to 22).toString(), byteArrayOf(0xf8.toByte())).encode()))
            runCurrent(); r.advance(200)
            assertTrue(observed.last() is GlassesEvent.Audio)
            assertTrue(stopped)
            assertTrue(r.link.writes.any { it.business == 0x17 && Envelope.decode(it.payload).type == 1 })
        } finally { r.close(); runtime.close() }
    }
    @Test fun pageChangesWaitForSuspendedReducerHandler() = runTest {
        val r = Rig(this, "TEXT")
        val gate = CompletableDeferred<Unit>()
        var waiting = false
        try {
            r.connect()
            val body = kotlinx.serialization.json.Json.parseToJsonElement(Envelope.decode(r.link.textOpens().last().payload).json) as kotlinx.serialization.json.JsonObject
            val sid = body.string("sid")!!
            r.link.event(0x13, 8, obj("sid" to sid, "code" to 1, "effective_config" to obj("font_size" to 1, "content_width" to 100, "max_lines" to 7)))
            runCurrent()
            r.link.fail = { if (it.business == 0x13 && Envelope.decode(it.payload).type == 5) { waiting = true; gate.await() } }
            r.transport.showGrid(listOf("before")); r.advance(600)
            assertTrue(waiting)
            r.advance(4000) // Timer ticks must not fill the command queue while this handler is suspended.
            r.transport.showGlyphExperiment(listOf("after")); r.transport.reclaim(); runCurrent()
            assertFalse(r.link.writes.any { it.business == 0x13 && Envelope.decode(it.payload).type == 3 })
            gate.complete(Unit); runCurrent()
            assertTrue(r.link.writes.any { it.business == 0x13 && Envelope.decode(it.payload).type == 3 })
        } finally { gate.complete(Unit); r.close() }
    }
    @Test fun armedLifeLogRasterRefusalAppearsInExperimentLog() = runTest {
        val r = Rig(this, "TEXT")
        val output = RasterOutput(r.context, r.scope, r.link, java.util.concurrent.atomic.AtomicInteger(),
            { true }, {}, {}, lifeLogArmed = { true })
        try {
            output.begin(); runCurrent()
            r.link.fail = { if (it.business == 0xf0) error("Glassly PAGE refused (1)") }
            output.forceTry(RasterKind.GLASSLY); r.advance(700)
            assertTrue(DisplayExperimentLog(r.context).read().contains("raster refused while LifeLog armed"))
        } finally { output.dispose(); r.close() }
    }

    @Test fun micStartNeverWaitsOnARasterCloseAndReducerStillProcessesEvents() = runTest {
        val r = Rig(this); r.link.turbo = true
        r.link.fail = { frame ->
            if (frame.business == 15 && Tvp2Codec.decode(frame.payload, false)?.op == 2)
                withTimeout(10_000) { delay(20_000) }
        }
        try {
            r.connect(); r.advance(2800)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
            r.transport.mic.start {}; runCurrent(); r.advance(5100)
            assertTrue(r.transport.mic.isCapturing())
            assertFalse(r.transport.raster.suspended)
            assertEquals(RasterKind.TVP2, r.transport.raster.selected.value)
            assertSame(r.transport.mic, io.lampyr.glasses.audio.MicRegistry.glasses)
            r.link.event(9, 1, obj("BatteryLevel" to 61)); runCurrent()
            assertEquals(61, r.transport.deviceStatusFlow.value.battery)
        } finally { r.close() }
    }
    @Test fun reconnectDisarmsPreviouslyArmedLifeLogBeforeProbingAgain() = runTest {
        val r = Rig(this)
        val runtime = r.ambient("lifelog")
        try {
            r.connect(); r.advance(3500)
            r.link.disconnect(); runCurrent()
            val before = r.link.writes.size
            r.link.firmware = "new-stock"; r.connect(); r.advance(3500)
            val reconnect = r.link.writes.drop(before)
            val disarm = reconnect.indexOfFirst { it.business == 15 && Tvp2Codec.decode(it.payload, false) == null && Envelope.decode(it.payload).json.contains("life_log_switch") }
            val probe = reconnect.indexOfFirst { it.business == 15 && Tvp2Codec.decode(it.payload, false)?.op == 1 }
            assertTrue(disarm >= 0 && probe > disarm)
            val payload = kotlinx.serialization.json.Json.parseToJsonElement(Envelope.decode(reconnect[disarm].payload).json) as kotlinx.serialization.json.JsonObject
            assertEquals(0, (payload["payload"] as kotlinx.serialization.json.JsonObject).int("value"))
        } finally { r.close(); runtime.close() }
    }

    @Test fun captureRequestedBeforeConnectedSignalIsReducedStillStartsCaptionMic() = runTest {
        val r = Rig(this, "TEXT")
        try {
            r.link.connect("device-a")
            assertTrue(r.transport.mic.start {})
            runCurrent()
            assertEquals(1, r.link.textOpens().size)
            assertEquals(1, Envelope.decode(r.link.textOpens().single().payload).type)
        } finally { r.close() }
    }
    @Test fun unsolicitedSuccessfulGlasslyCloseHoldsDisplayUntilControllerReclaim() = runTest {
        val r = Rig(this); r.link.glassly = true
        try {
            r.connect(); r.advance(1800)
            assertEquals(RasterKind.GLASSLY, r.transport.raster.selected.value)
            val opens = r.link.textOpens().size
            r.link.frames.emit(RayneoFrame(0xf0, 1, byteArrayOf(0x13, 0, 0)))
            runCurrent(); r.advance(500)
            assertEquals(null, r.transport.raster.selected.value)
            assertEquals(opens, r.link.textOpens().size)
            r.transport.reclaim(); runCurrent(); r.advance(800)
            assertEquals(RasterKind.GLASSLY, r.transport.raster.selected.value)
        } finally { r.close() }
    }

    /** the user relies on this: LifeLog is paused for every transcript or memo capture and re-armed when it ends. */
    @Test fun lifeLogPausesForEachGlassesMicCaptureAndResumesAfterIt() = runTest {
        val r = Rig(this); r.link.turbo = true
        val runtime = r.ambient("lifelog")
        fun switches() = r.link.writes.mapNotNull { frame ->
            if (frame.business != 15 || Tvp2Codec.decode(frame.payload, false) != null) return@mapNotNull null
            Envelope.decode(frame.payload).json.takeIf { it.contains("life_log_switch") }?.let { if (it.contains("\"value\":1")) 1 else 0 }
        }
        try {
            r.connect(); r.advance(2800)
            assertEquals(1, switches().last())
            repeat(2) {
                assertTrue(r.transport.mic.start {}); runCurrent(); r.advance(200)
                assertEquals(0, switches().last()) // Paused while the microphone captures.
                r.transport.mic.stop(); runCurrent(); r.advance(800)
                assertEquals(1, switches().last()) // Re-armed afterwards.
            }
        } finally { r.close(); runtime.close() }
    }
}
