package io.lampyr.glasses

import android.content.Context
import android.content.ContextWrapper
import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Media start workstream: Spotify cold start on the phone and YouTube start with Focus mode / lock / fallback paths. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneMediaStarterTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeApps : PhoneApps {
        val status = mutableMapOf(SPOTIFY_PACKAGE to AppStatus.READY, YOUTUBE_PACKAGE to AppStatus.READY)
        var locked = false
        var canWake = true
        var openWorks = true
        var playingAfterChecks = Int.MAX_VALUE
        val calls = mutableListOf<String>()
        private var checks = 0
        override fun status(pkg: String) = status.getValue(pkg)
        override fun locked() = locked
        override fun wakeSpotify(): Boolean { calls += "wake"; return canWake }
        override fun openSpotify(uri: String): Boolean { calls += "openSpotify:$uri"; return true }
        override fun releaseSpotify() { calls += "release" }
        override fun openYouTube(videoId: String): Boolean { calls += "openYouTube:$videoId"; return openWorks }
        override fun youtubePlaying() = ++checks >= playingAfterChecks
        override fun notifyYouTube(videoId: String): Boolean { calls += "notify:$videoId"; return true }
        var notices = 0
        override fun clearYouTubeNotice() { notices++ }
        /** Packages whose session offers ACTION_PLAY_FROM_URI, and whether asking it actually starts playback. */
        val sessionUri = mutableMapOf<String, Boolean>()
        val sessionPlaying = mutableSetOf<String>()
        override fun playing(pkg: String) = pkg in sessionPlaying
        override fun playFromUri(pkg: String, uri: String): Boolean {
            val starts = sessionUri[pkg] ?: return false
            calls += "uri:$pkg:$uri"
            if (starts) { sessionPlaying += pkg; if (pkg == YOUTUBE_PACKAGE) playingAfterChecks = 0 }
            return true
        }
    }

    private val phone = Device("ph", "Pixel 10 Pro", "Smartphone")
    private val desk = Device("dk", "DESKTOP", "Computer")

    /** Records each pause on a virtual clock and lets [onPause] change the world (devices appearing). */
    private class Clock { var ms = 0L; val pauses = mutableListOf<Long>() }
    private fun starter(apps: FakeApps, clock: Clock, model: String = "Pixel 10 Pro", onPause: (Long) -> Unit = {}) =
        PhoneMediaStarter(apps, { clock.pauses += it; clock.ms += it; onPause(clock.ms) }, model, now = { clock.ms })

    @Test fun spotifyPausedByFocusModeFailsClearlyWithoutStartingAnything() = runTest {
        val apps = FakeApps().apply { status[SPOTIFY_PACKAGE] = AppStatus.SUSPENDED }
        val result = starter(apps, Clock()).startSpotify("spotify:playlist:p", { error("no poll") }, { error("no play") })
        assertFalse(result.ok); assertEquals(PhoneMediaStarter.SPOTIFY_PAUSED, result.message); assertTrue(apps.calls.isEmpty())
    }

    @Test fun spotifyNotInstalledKeepsTheDevicePicker() = runTest {
        val apps = FakeApps().apply { status[SPOTIFY_PACKAGE] = AppStatus.MISSING }
        val result = starter(apps, Clock()).startSpotify("spotify:playlist:p", { emptyList() }, {})
        assertEquals(SPOTIFY_CHOOSE_DEVICE, result.id); assertTrue(apps.calls.isEmpty())
    }

    @Test fun coldStartWakesSpotifyInTheBackgroundPollsUntilThePhoneRegistersThenPlaysThere() = runTest {
        val apps = FakeApps(); val clock = Clock(); val played = mutableListOf<String>()
        lateinit var s: PhoneMediaStarter
        val statuses = mutableListOf<String?>()
        s = starter(apps, clock)
        val result = s.startSpotify("spotify:playlist:p", {
            statuses += s.status
            if (clock.ms >= 3000) listOf(desk, phone) else listOf(desk)
        }, { played += it })
        assertTrue(result.ok)
        assertEquals(listOf("ph"), played)
        assertEquals(listOf("wake", "release"), apps.calls) // No activity start: the background bind was enough.
        assertEquals(listOf(1500L, 1500L), clock.pauses)
        assertTrue(statuses.all { it == PhoneMediaStarter.STARTING_SPOTIFY })
        assertNull(s.status)
    }

    @Test fun coldStartOpensTheSpotifyAppWhenTheBackgroundBindDoesNotRegisterADevice() = runTest {
        val apps = FakeApps(); val clock = Clock(); val played = mutableListOf<String>()
        val result = starter(apps, clock).startSpotify("spotify:playlist:p", {
            if (apps.calls.any { it.startsWith("openSpotify") } && clock.ms >= 7500) listOf(phone) else emptyList()
        }, { played += it })
        assertTrue(result.ok); assertEquals(listOf("ph"), played)
        assertEquals(listOf("wake", "openSpotify:spotify:playlist:p", "release"), apps.calls)
    }

    @Test fun coldStartOpensSpotifyRightAwayWhenItHasNoBrowserService() = runTest {
        val apps = FakeApps().apply { canWake = false }
        val result = starter(apps, Clock()).startSpotify("spotify:collection", { listOf(phone) }, {})
        assertTrue(result.ok); assertEquals(listOf("wake", "openSpotify:spotify:collection", "release"), apps.calls)
    }

    @Test fun coldStartRetriesOnceWhenTheNewDeviceRejectsTheFirstPlay() = runTest {
        val apps = FakeApps(); var attempts = 0
        val result = starter(apps, Clock()).startSpotify("spotify:playlist:p", { listOf(phone) }, {
            if (++attempts == 1) throw GatewayFailure("Device not found", 404)
        })
        assertTrue(result.ok); assertEquals(2, attempts)
        // A second rejection is reported, not retried forever.
        attempts = 0
        val failed = runCatching { starter(apps, Clock()).startSpotify("spotify:playlist:p", { listOf(phone) }, { attempts++; throw GatewayFailure("No active Spotify device", 409) }) }
        assertTrue(failed.isFailure); assertEquals(2, attempts)
    }

    @Test fun coldStartTimeoutShowsThePickerOrAsksToUnlock() = runTest {
        val apps = FakeApps(); val clock = Clock()
        val result = starter(apps, clock).startSpotify("spotify:playlist:p", { listOf(desk) }, { error("must not play") })
        assertFalse(result.ok); assertEquals(SPOTIFY_CHOOSE_DEVICE, result.id)
        assertTrue(clock.ms in PhoneMediaStarter.SPOTIFY_WAIT_MS..PhoneMediaStarter.SPOTIFY_WAIT_MS + PhoneMediaStarter.POLL_MS)
        assertEquals(1, apps.calls.count { it.startsWith("openSpotify") })
        apps.locked = true
        val locked = starter(apps, Clock()).startSpotify("spotify:playlist:p", { throw GatewayFailure("offline", 502) }, {})
        assertFalse(locked.ok); assertEquals(PhoneMediaStarter.UNLOCK_FOR_SPOTIFY, locked.message)
    }

    @Test fun slowDevicePollsDoNotStretchTheSpotifyWaitPastItsBudget() = runTest {
        // Each /spotify/devices round trip takes 4 s of real time: the wait still ends after about 15 s, not 11 polls later.
        val apps = FakeApps(); val clock = Clock(); var polls = 0
        val result = starter(apps, clock).startSpotify("spotify:playlist:p", { polls++; clock.ms += 4000; listOf(desk) }, { error("must not play") })
        assertEquals(SPOTIFY_CHOOSE_DEVICE, result.id)
        assertTrue("polls=$polls", polls <= 4)
        assertTrue(clock.ms <= PhoneMediaStarter.SPOTIFY_WAIT_MS + 4000 + PhoneMediaStarter.POLL_MS)
    }

    @Test fun aNewYouTubePickClearsTheEarlierNotification() = runTest {
        val apps = FakeApps().apply { playingAfterChecks = 1 }
        assertTrue(starter(apps, Clock()).startYouTube("vid1").ok)
        assertEquals(1, apps.notices)
    }

    @Test fun phoneDeviceIsTheSmartphoneMatchingThisModel() {
        val s = starter(FakeApps(), Clock(), model = "Pixel 10 Pro")
        val other = Device("o", "Galaxy S30", "Smartphone")
        assertEquals("ph", s.phoneDevice(listOf(desk, other, phone))?.id)
        assertNull(s.phoneDevice(listOf(desk)))
        assertEquals("o", starter(FakeApps(), Clock(), model = "").phoneDevice(listOf(desk, other))?.id)
        assertNull(starter(FakeApps(), Clock(), model = "").phoneDevice(listOf(other, phone))) // Ambiguous: let the wearer pick.
    }

    @Test fun youtubePausedByFocusModeFailsWithoutOpening() = runTest {
        val apps = FakeApps().apply { status[YOUTUBE_PACKAGE] = AppStatus.SUSPENDED }
        val result = starter(apps, Clock()).startYouTube("vid1")
        assertFalse(result.ok); assertEquals(PhoneMediaStarter.YOUTUBE_PAUSED, result.message); assertTrue(apps.calls.isEmpty())
    }

    @Test fun youtubeStartsAndWaitsForItsSession() = runTest {
        val apps = FakeApps().apply { playingAfterChecks = 3 }; val clock = Clock()
        val result = starter(apps, clock).startYouTube("vid1")
        assertTrue(result.ok); assertNull(result.message)
        assertEquals(listOf("openYouTube:vid1"), apps.calls); assertEquals(3000L, clock.ms)
    }

    @Test fun youtubeOnALockedPhoneOpensBehindTheLockScreenAndAsksToUnlock() = runTest {
        val apps = FakeApps().apply { locked = true }; val clock = Clock()
        val result = starter(apps, clock).startYouTube("vid1")
        assertTrue(result.ok); assertEquals(PhoneMediaStarter.UNLOCK_FOR_YOUTUBE, result.message)
        // No session offers a URI start: the notification waits on the lock screen for the unlock.
        assertEquals(listOf("openYouTube:vid1", "notify:vid1"), apps.calls); assertEquals(0L, clock.ms)
    }

    @Test fun youtubeOnALockedPhoneStartsThroughItsSessionWhenItCan() = runTest {
        val apps = FakeApps().apply { locked = true; sessionUri[YOUTUBE_PACKAGE] = true }; val clock = Clock()
        val result = starter(apps, clock).startYouTube("vid1")
        assertTrue(result.ok); assertNull(result.message)
        assertEquals(listOf("openYouTube:vid1", "uri:$YOUTUBE_PACKAGE:https://www.youtube.com/watch?v=vid1"), apps.calls)
        // A session that accepts the URI but never plays still ends with the unlock request.
        val idle = FakeApps().apply { locked = true; sessionUri[YOUTUBE_PACKAGE] = false }; val idleClock = Clock()
        assertEquals(PhoneMediaStarter.UNLOCK_FOR_YOUTUBE, starter(idle, idleClock).startYouTube("vid1").message)
        assertEquals(PhoneMediaStarter.YOUTUBE_SESSION_WAIT_MS, idleClock.ms)
        assertEquals("notify:vid1", idle.calls.last())
        // Android refused the activity while locked: the session alone can still start it.
        val refused = FakeApps().apply { locked = true; openWorks = false; sessionUri[YOUTUBE_PACKAGE] = true }
        assertNull(starter(refused, Clock()).startYouTube("vid1").message)
    }

    @Test fun youtubeOpenedButIdleIsNudgedThroughItsSessionOnce() = runTest {
        val apps = FakeApps().apply { sessionUri[YOUTUBE_PACKAGE] = true }; val clock = Clock()
        val result = starter(apps, clock).startYouTube("vid1")
        assertTrue(result.ok); assertNull(result.message)
        assertEquals(listOf("openYouTube:vid1", "uri:$YOUTUBE_PACKAGE:https://www.youtube.com/watch?v=vid1"), apps.calls)
        assertEquals(PhoneMediaStarter.YOUTUBE_NUDGE_AFTER_MS + PhoneMediaStarter.POLL_MS, clock.ms)
    }

    @Test fun spotifyColdStartPlaysThroughSpotifysOwnSessionWithoutTheWebApi() = runTest {
        val apps = FakeApps().apply { sessionUri[SPOTIFY_PACKAGE] = true }; val clock = Clock(); val played = mutableListOf<String>()
        val result = starter(apps, clock).startSpotify("spotify:playlist:p", { emptyList() }, { played += it })
        assertTrue(result.ok); assertTrue(played.isEmpty())
        assertEquals(listOf("wake", "uri:$SPOTIFY_PACKAGE:spotify:playlist:p", "release"), apps.calls)
        assertEquals(listOf(1500L), clock.pauses)
        // The session took the URI but did not play: the Connect device path still finishes the start.
        val slow = FakeApps().apply { sessionUri[SPOTIFY_PACKAGE] = false }; val slowClock = Clock()
        val r2 = starter(slow, slowClock).startSpotify("spotify:playlist:p", { if (slowClock.ms >= 1500) listOf(phone) else emptyList() }, { played += it })
        assertTrue(r2.ok); assertEquals(listOf("ph"), played)
    }

    @Test fun youtubeThatNeverStartsFallsBackToANotification() = runTest {
        val apps = FakeApps(); val clock = Clock()
        val result = starter(apps, clock).startYouTube("vid1")
        assertTrue(result.ok); assertEquals(PhoneMediaStarter.TAP_NOTIFICATION, result.message)
        assertEquals(listOf("openYouTube:vid1", "notify:vid1"), apps.calls); assertEquals(PhoneMediaStarter.YOUTUBE_WAIT_MS, clock.ms)
        val blocked = FakeApps().apply { openWorks = false }
        assertEquals(PhoneMediaStarter.TAP_NOTIFICATION, starter(blocked, Clock()).startYouTube("vid2").message)
        assertEquals(listOf("openYouTube:vid2", "notify:vid2"), blocked.calls)
    }

    // ---- LiveDataStore wiring ----

    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }

    @Test fun liveStoreColdStartsSpotifyOnThePhoneAfter409() = runTest {
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        var devicePolls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val buffer = Buffer(); request.body?.writeTo(buffer)
            val body = buffer.readUtf8(); val key = "${request.method} ${request.url.encodedPath}"
            sent += "$key $body"
            val (code, json) = when {
                key == "POST /spotify/play" && !body.contains("deviceId") -> 409 to """{"error":"No active Spotify device"}"""
                key == "GET /spotify/devices" -> 200 to if (++devicePolls < 2) "[]" else """[{"id":"ph","name":"Pixel 10 Pro","type":"Smartphone","active":false}]"""
                key == "GET /spotify/now" -> 200 to "null"
                else -> 200 to """{"ok":true}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val apps = FakeApps()
        val data = LiveDataStore(context(), backgroundScope, GatewayClient("https://fixture.example", "synthetic-token", http),
            { Instant.parse("2026-11-12T20:00:00Z").toEpochMilli() }, { testScheduler.currentTime / 1000.0 }, startBackground = false, phoneApps = { apps })
        try {
            val result = data.media.play("spotify", "spotify:playlist:abc")
            assertTrue(result.message, result.ok)
            assertTrue(sent.any { it.startsWith("POST /spotify/play") && it.contains("\"deviceId\":\"ph\"") && it.contains("spotify:playlist:abc") })
            assertEquals(listOf("wake", "release"), apps.calls)
            assertEquals(listOf("ph"), data.media.devices.map { it.id })
            apps.status[YOUTUBE_PACKAGE] = AppStatus.SUSPENDED
            assertEquals(PhoneMediaStarter.YOUTUBE_PAUSED, data.media.play("youtube", "vid1").message)
        } finally { data.close() }
    }

    // ---- Glasses UI ----

    @Test fun glassesShowStartProgressAndTheUnlockMessage() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val now = Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()
        val fake = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { now }, scope)
        val gate = CompletableDeferred<Unit>()
        var status: String? = PhoneMediaStarter.OPENING_YOUTUBE
        val media = object : MediaRepository by fake.media {
            override val nowPlaying: NowPlaying? get() = null
            override val startStatus get() = status
            override fun videos(key: String) = listOf(Video("vid1", "A video", "Channel", 60))
            override suspend fun play(source: String, id: String): ActionResult { gate.await(); status = null; return ActionResult(true, PhoneMediaStarter.UNLOCK_FOR_YOUTUBE) }
        }
        val ui = Ui(object : DataStore by fake { override val media = media }, scope = scope, nowS = { 0.0 }, nowMs = { now }, zone = ZoneId.of("America/Chicago"))
        ui.display = Display.Text()
        try {
            ui.push(YouTube(ui))
            ui.top.key("sel")
            assertEquals(PhoneMediaStarter.OPENING_YOUTUBE, ui.currentToast)
            gate.complete(Unit)
            val np = ui.top as NowPlayingScreen
            assertEquals(PhoneMediaStarter.UNLOCK_FOR_YOUTUBE, np.openingText)
            val grid = TextGrid(26, 7, false).also { np.drawText(it) }.lines().joinToString("\n")
            assertTrue(grid, grid.contains("Unlock the phone"))
            assertFalse(grid.contains("Nothing playing"))
        } finally { ui.close() }
    }
}
