package io.lampyr.glasses.debug

import android.content.Context
import android.net.Uri
import io.lampyr.glasses.audio.FakeDictation
import io.lampyr.glasses.data.FakeDataStore
import io.lampyr.glasses.glasses.*
import io.lampyr.glasses.glasses.protocol.*
import io.lampyr.glasses.input.InputEngine
import io.lampyr.glasses.service.GlassesService
import io.lampyr.glasses.ui.Ui
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugBridgeTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val main = StandardTestDispatcher()
    @Before fun setup() {
        Dispatchers.setMain(main)
        GlassesService.storeFactory = { c, s -> FakeDataStore(c, scope = s) }
    }
    @After fun cleanup() { GlassesService.storeFactory = null; Dispatchers.resetMain() }
    private suspend fun request(path: String) = JSONObject(DebugBridge(context).execute(Uri.parse("content://io.lampyr.glasses.debug$path")).bytes.toString(Charsets.UTF_8))

    @Test fun callerPolicyRejectsAppAndAcceptsOnlyShellAndRoot() {
        DebugProvider.checkCaller(0); DebugProvider.checkCaller(2000)
        for (uid in listOf(1000, 10000, android.os.Process.myUid())) {
            assertThrows(SecurityException::class.java) { DebugProvider.checkCaller(uid) }
        }
    }
    @Test fun providerEntryPointsCheckBinderUidBeforeRouting() {
        val provider = DebugProvider()
        val uri = Uri.parse("content://io.lampyr.glasses.debug/ping")
        org.robolectric.shadows.ShadowBinder.setCallingUid(10000)
        assertThrows(SecurityException::class.java) { provider.getType(uri) }
        assertThrows(SecurityException::class.java) { provider.openFile(uri, "r") }
        assertThrows(SecurityException::class.java) { provider.query(uri, null, null, null, null) }
        assertThrows(SecurityException::class.java) { provider.call("x", null, null) }
        org.robolectric.shadows.ShadowBinder.setCallingUid(2000)
        assertEquals("application/json", provider.getType(uri))
        org.robolectric.shadows.ShadowBinder.setCallingUid(0)
        assertEquals("image/png", provider.getType(uri.buildUpon().path("frame.png").build()))
    }
    @Test fun pingUnknownRouteAndOrdinaryErrorsAreJson() = runTest {
        assertTrue(request("/ping").getBoolean("ok"))
        assertTrue(request("/ping").getBoolean("debug"))
        val missing = request("/not-an-endpoint")
        assertFalse(missing.getBoolean("ok")); assertTrue(missing.getJSONObject("endpoints").has("/ping"))
        val error = request("/pref?file=invalid&key=x&value=y")
        assertFalse(error.getBoolean("ok")); assertTrue(error.getString("detail").contains("file must"))
    }
    @Test fun prefCommitsAllTypesAndRemoves() = runTest {
        for ((type, value) in listOf("string" to "hello", "bool" to "true", "int" to "42", "long" to "9000000000", "float" to "1.5")) {
            val result = request("/pref?file=glasses&key=test.$type&value=$value&type=$type")
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertNotNull(context.getSharedPreferences("glasses", 0).all["test.$type"])
        }
        assertTrue(request("/pref?file=app&key=previewText&value=true&type=bool").getBoolean("value"))
        assertTrue(request("/pref?file=glasses&key=test.int&type=remove").isNull("value"))
    }
    @Test fun prefRefusesCredentialWritesAndStateRedactsTokens() = runTest {
        val prefs = context.getSharedPreferences("live-settings", 0)
        prefs.edit().putString("token", "CIPHERTEXT_MUST_NOT_LEAK").putString("gatewayCredential", "PRIVATE").commit()
        val state = request("/state")
        assertFalse(state.toString().contains("CIPHERTEXT_MUST_NOT_LEAK"))
        assertFalse(state.toString().contains("PRIVATE"))
        assertTrue(state.getJSONObject("prefs").getJSONObject("app").getBoolean("tokenPresent"))
        assertFalse(request("/pref?file=app&key=token&value=secret").getBoolean("ok"))
        assertFalse(request("/pref?file=glasses&key=glasses.phoneId&value=x").getBoolean("ok"))
    }
    @Test fun stateHasStableShapeWithoutStartingAService() = runTest {
        assertNull(GlassesService.instance)
        val state = request("/state")
        assertTrue(state.toString(), state.getBoolean("ok"))
        for (key in listOf("app", "android", "permissions", "bluetooth", "prefs", "glasses", "ui", "screen", "controller", "ambient", "trace")) assertTrue(key, state.has(key))
        assertFalse(state.getJSONObject("app").getBoolean("serviceRunning"))
        assertTrue(state.getJSONObject("permissions").has(android.Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(state.getJSONObject("permissions").has(android.Manifest.permission.BLUETOOTH))
        assertNull(GlassesService.instance)
    }
    @Test fun stateAndKeyRouteWorkWithFakeDataService() = runTest {
        val owner = Robolectric.buildService(GlassesService::class.java).create()
        try {
            runCurrent()
            val state = request("/state")
            assertTrue(state.toString(), state.getBoolean("ok"))
            assertTrue(state.getJSONObject("app").getBoolean("serviceRunning"))
            assertEquals("Home", state.getJSONObject("ui").getJSONArray("stack").getString(0))
            val result = request("/key?b=star&a=tap")
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("Apps", result.getJSONArray("stack").getString(1))
            assertEquals("release letter=star source=adb", owner.get().input.lastKeyEvent)
            assertFalse(owner.get().input.hasKeysDown)
        } finally { owner.destroy(); runCurrent() }
    }
    @Test fun keyUsesInputEngineAndCancellationReleasesHeldButton() = runTest {
        val store = FakeDataStore(context, scope = backgroundScope)
        val ui = Ui(store, scope = backgroundScope)
        val input = InputEngine(ui, FakeDictation(store, backgroundScope))
        val bridge = DebugBridge(context)
        bridge.key(input, "star", "tap", 600)
        assertEquals("apps", ui.top.kind)
        val job = launch { bridge.key(input, "r2", "hold", 600) }
        runCurrent(); assertTrue(ui.mediaLayer)
        job.cancelAndJoin(); assertFalse(input.hasKeysDown); assertFalse(ui.mediaLayer)
        input.close(); ui.close()
    }
    @Test fun otaWritesAreRefusedThroughSendEndpointForBothChannelsBeforeConnecting() = runTest {
        for (channel in listOf("rfcomm", "ble")) {
            val result = request("/glasses/send?business=9&type=3&json=%7B%7D&channel=$channel")
            assertFalse(result.getBoolean("ok")); assertTrue(result.getString("error").contains("read-only OTA"))
            assertNull(GlassesService.instance)
        }
        for (type in listOf(1, 2, 11)) assertEquals(type, DebugBridge.probeCommand(Uri.parse("content://io.lampyr.glasses.debug/glasses/send?business=9&type=$type&json=%7B%7D")).envelope.type)
    }
    @Test fun traceFilteringAndLimitAreAppliedAfterCategorySelection() = runTest {
        val seq = Trace.latest
        Trace.log("One", "one"); Trace.log("Wanted/child", "two"); Trace.log("Wanted", "three")
        val result = request("/trace?since=$seq&cat=Wanted&max=1")
        assertEquals(1, result.getJSONArray("entries").length())
        assertEquals("two", result.getJSONArray("entries").getJSONObject(0).getString("msg"))
    }
    @Test fun selectionMatchesSharedUiAction() = runTest {
        val result = request("/glasses/select?ble=AA:BB:CC:DD:EE:01&classic=AA:BB:CC:DD:EE:02&name=RayNeo")
        assertTrue(result.toString(), result.getBoolean("ok"))
        val prefs = context.getSharedPreferences("glasses", 0)
        val actual = prefs.all.toMap()
        prefs.edit().clear().commit()
        GlassesSetupActions.select(context, RayNeoDevice("RayNeo", "AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"))
        assertEquals(actual, prefs.all)
    }
    @Test fun experimentRouterAndUiControllerShareLogAndRecordPath() = runTest {
        val bridge = DebugBridge(context)
        fun path(value: String) = Uri.parse("content://io.lampyr.glasses.debug$value")
        val result = JSONObject(bridge.execute(path("/experiment/record?result=PASS&note=from-adb")).bytes.toString(Charsets.UTF_8))
        assertTrue(result.getBoolean("ok"))
        assertTrue(DisplayExperimentController(context).readLog().contains("PASS from-adb"))
        assertEquals(DisplayExperimentController(context).readLog(), JSONObject(bridge.execute(path("/experiment/log")).bytes.toString(Charsets.UTF_8)).getString("log"))
        assertFalse(JSONObject(bridge.execute(path("/experiment/record?result=INVALID")).bytes.toString(Charsets.UTF_8)).getBoolean("ok"))
    }
    private class FakeLink : TransportLink {
        override val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        override val lastError = MutableStateFlow<String?>(null)
        override val frames = MutableSharedFlow<RayneoFrame>()
        val writes = mutableListOf<RayneoFrame>()
        override fun connect(address: String) {}
        override fun disconnect() {}
        override fun retryIfIdle() {}
        override fun close() {}
        override suspend fun send(frame: RayneoFrame) { writes += frame }
        override suspend fun sendBle(frame: RayneoFrame) { writes += frame }
    }
    @Test fun headlessExperimentsUseStockCommandsAndGateForcedRaster() = runTest {
        context.getSharedPreferences("glasses", 0).edit().putString("display.mode", "TEXT").commit()
        val link = FakeLink()
        val scope = CoroutineScope(SupervisorJob() + main)
        val real = RayNeoTransport(context, link, scope) { testScheduler.currentTime }
        val controller = DisplayExperimentController(context) { real }
        try {
            runCurrent()
            controller.execute("weather", mapOf("code" to "501"))
            val expected = Commands.currentWeather("Companion probe", 501, 20, System.currentTimeMillis() / 1000)
            assertEquals(expected.envelope.type, Envelope.decode(link.writes.last().payload).type)
            assertTrue(Envelope.decode(link.writes.last().payload).json.contains("501"))
            try { controller.execute("pattern", mapOf("kind" to "tvp2")); fail("Confirmation required") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("force=1")) }
            controller.execute("widget", mapOf("install" to "1"))
            assertEquals(DisplayExperiments.widget(true).envelope, Envelope.decode(link.writes.last().payload))
        } finally { real.close(); scope.cancel() }
    }
    @Test fun sentTextMetadataRetainsWrittenLines() {
        assertEquals("Hello", DisplayMirror.text("Hello\nworld", 26, 7).text.lines().first().trim())
        assertEquals("\uff28\uff49\u3000\u2588", DisplayMirror.text("\uff28\uff49\u3000\u2588", 26, 7).text.lines().first().trimEnd(' '))
    }
    @Test fun traceFilesAppendAcrossRestartRotateByBytesAndFlushErrors() {
        val directory = java.io.File(context.filesDir, "debug").apply { mkdirs() }
        val current = java.io.File(directory, "trace.jsonl")
        current.writeText("{\"marker\":true}\n")
        var files = DebugTraceFiles(context)
        files.append(Trace.Entry(1, 1, 1, "Test", "error", isError = true))
        assertTrue(current.readText().startsWith("{\"marker\":true}\n"))
        assertTrue(current.readText().contains("error")) // error append flushes synchronously
        files.close()
        files = DebugTraceFiles(context)
        try {
            assertTrue(current.readText().contains("marker"))
            // UTF-8, rather than character, size decides when to rotate.
            val message = "\u00e9".repeat(600_000)
            repeat(8) { files.append(Trace.Entry(it.toLong() + 2, 1, 1, "Test", message)) }
            files.flush()
            val previous = java.io.File(directory, "trace.previous.jsonl")
            assertTrue(previous.exists())
            assertTrue(current.length() <= 4 * 1024 * 1024)
            assertTrue(previous.length() <= 4 * 1024 * 1024)
            assertEquals(9L, JSONObject(current.readLines().last()).getLong("seq"))
            assertEquals(2, directory.listFiles()!!.size)
        } finally { files.close() }
    }
}
