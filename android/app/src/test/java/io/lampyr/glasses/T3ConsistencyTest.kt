package io.lampyr.glasses

import android.content.Context
import android.content.ContextWrapper
import io.lampyr.glasses.data.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
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

/** Requirement 8: one source for machine counts, precise T3 errors, project rows and new-thread opening. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class T3ConsistencyTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }
    private val epoch = Instant.parse("2026-11-12T20:00:00Z").toEpochMilli()
    private fun TestScope.store(f: FixtureGateway) = LiveDataStore(context(), backgroundScope, f.client, { epoch }, { testScheduler.currentTime / 1000.0 }, startBackground = false)

    @Test fun environmentsFollowThePolledMachineRowsWithoutOpeningEnvironments() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            assertTrue(data.t3.environments.isEmpty())
            data.t3.refreshThreads() // the poll; /t3/environments never fetched
            assertFalse(f.sent.any { it.path == "/t3/environments" })
            assertEquals(data.t3.machines.map { it.machine to it.online }, data.t3.environments.map { it.machine to it.online })
            assertEquals(2, data.t3.environments.size); assertEquals(1, data.t3.offlineCount)
            assertEquals("windows", data.t3.environments[0].os)
            assertEquals("network error", data.t3.environments[1].cause)
            // Opening Environments refreshes the same rows; an older gateway without os in machines still shows os.
            f.fixtures["GET /t3/threads"] = gatewayJson.parseToJsonElement("""{"machines":[{"machine":"desktop","label":"Desktop","online":false}],"threads":[]}""")
            assertTrue(data.t3.refreshEnvironments().ok)
            assertTrue(f.sent.any { it.path == "/t3/threads" } && f.sent.any { it.path == "/t3/environments" })
            assertEquals(listOf("desktop" to false), data.t3.environments.map { it.machine to it.online })
            assertEquals("windows", data.t3.environments.single().os)
        } finally { data.close() }
    }

    @Test fun t3ErrorsShowTheGatewaysMachineAndReason() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            f.status = 503; f.fixtures["POST /t3/threads/desktop/same/messages"] = obj("error" to str("Alex's MacBook Air unreachable (network error)"))
            assertEquals("Alex's MacBook Air unreachable (network error)", data.t3.send("desktop", "same", "hi").message)
            f.status = 403; f.fixtures["POST /t3/threads/desktop/same/messages"] = obj("error" to str("HomeDesktop is read-only; re-pair with write access"))
            assertEquals("HomeDesktop is read-only; re-pair with write access", data.t3.send("desktop", "same", "hi").message)
        } finally { data.close() }
        assertEquals("Check token", t3FailureText(403, """{"error":"Token not valid for this API"}"""))
        assertEquals("Check token", t3FailureText(401, """{"error":"Missing bearer token"}"""))
        assertEquals("Gateway busy · retry", t3FailureText(503, """{"error":"Authentication temporarily unavailable"}"""))
        assertEquals("Gateway error (502)", t3FailureText(502, "<html>"))
        assertEquals("Not available yet", t3FailureText(404, """{"error":"Not found"}"""))
        assertEquals("Project not found on Mac", t3FailureText(404, """{"error":"Project not found on Mac"}"""))
        assertEquals(60, t3FailureText(504, """{"error":"${"x".repeat(80)}"}""").length)
    }

    @Test fun rootCountComesFromMachinesAndIsBlankWithoutMachines() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fake = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { epoch }, scope)
        fun rootRight(machines: List<Environment>, environments: List<Environment>): String {
            val repo = object : T3Repository by fake.t3 { override val machines = machines; override val environments = environments }
            val ui = Ui(object : DataStore by fake { override val t3 = repo }, scope = scope, nowS = { 0.0 }, nowMs = { epoch }, zone = ZoneId.of("America/Chicago"))
            try { return T3Root(ui).rows().single { it.label == "Environments" }.right } finally { ui.close() }
        }
        val three = listOf(Environment("a", "A", true), Environment("b", "B", true), Environment("c", "C", false))
        assertEquals("2/3 online", rootRight(three, emptyList()))
        assertEquals("", rootRight(emptyList(), emptyList()))
        scope.cancel()
    }

    @Test fun environmentRowsNamePairingAndWriteAccess() {
        assertEquals("windows · online · read-only", environmentSubtitle(Environment("d", "Desk", true, "windows", canWrite = false)))
        assertEquals("ask Hermes to pair", environmentSubtitle(Environment("n", "New", false, paired = false, needsPairing = true)))
        assertEquals("offline, last seen Never seen (timed out) · needs re-pairing", environmentSubtitle(Environment("m", "Mac", false, lastSeenLabel = "Never seen", needsRepair = true, cause = "timed out")))
        assertEquals("linux · offline", environmentSubtitle(Environment("v", "VPS", false, "linux")))
    }

    @Test fun newThreadNamesMachinesOnlyWhenSeveralAndOpensTheStartedThread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fake = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { epoch }, scope)
        var projects = listOf(Project("mac", "p1", "Glasses", "Mac"), Project("vps", "p2", "Glasses", "VPS"))
        val repo = object : T3Repository by fake.t3 {
            override val projects get() = projects
            override suspend fun newThread(machine: String, projectId: String, text: String) = ActionResult(id = "new-$machine")
        }
        val ui = Ui(object : DataStore by fake { override val t3 = repo }, scope = scope, nowS = { 0.0 }, nowMs = { epoch }, zone = ZoneId.of("America/Chicago"))
        try {
            ui.push(T3Root(ui)); val pick = NewThread(ui); ui.push(pick)
            assertEquals(listOf("Mac", "VPS"), pick.rows().map { it.right })
            pick.rows()[1].open!!.invoke()
            val prompt = ui.top
            assertEquals("ok", prompt.talk!!.send("Say hi").let { if (it.ok) "ok" else it.message })
            assertTrue(ui.top is T3Thread); assertEquals(ThreadKey("vps", "new-vps"), (ui.top as T3Thread).threadKey)
            projects = listOf(Project("mac", "p1", "Glasses", "Mac"))
            assertEquals(listOf(""), NewThread(ui).rows().map { it.right })
            projects = emptyList()
            assertEquals("No projects · check Environments", NewThread(ui).rows().single().label)
        } finally { ui.close(); scope.cancel() }
    }
}
