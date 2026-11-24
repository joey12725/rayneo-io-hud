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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Requirements 9 (Spotify) and 10 (Audiobookshelf): live lists, device picker, ABS rows and progress sync. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaWorkstreamTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fake by lazy { FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { now }, scope) }
    private fun ui(media: MediaRepository) = Ui(object : DataStore by fake { override val media = media }, scope = scope, nowS = { 0.0 }, nowMs = { now }, zone = ZoneId.of("America/Chicago")).apply { display = Display.Text() }
    private fun text(view: View): String = TextGrid(26, 7, false).also { view.drawText(it); assertFalse("Overflow in ${view.id}: ${it.lines()}", it.overflowed) }.lines().joinToString("\n")

    @Test fun spotifyShowsLibraryPlaylistsSeparatelyAndReportsGatewayErrors() {
        var errors = emptyList<String>()
        val media = object : MediaRepository by fake.media {
            override val nowPlaying: NowPlaying? get() = null
            override val playlists get() = listOf(Playlist("spotify:collection", "Liked Songs"), Playlist("spotify:playlist:r", "Recent mix"))
            override val libraryPlaylists get() = listOf(Playlist("spotify:playlist:r", "Recent mix"), Playlist("spotify:playlist:l", "Library mix"))
            override val spotifyErrors get() = errors
        }
        val ui = ui(media)
        try {
            val view = Spotify(ui)
            assertEquals(listOf("Liked Songs", "RECENT PLAYLISTS", "Recent mix", "PLAYLISTS", "Library mix"), view.rows().map { it.label })
            text(view)
            errors = listOf("playback")
            assertEquals(5, view.rows().size) // Playback failures are not a list failure.
            errors = listOf("playlists", "recent")
            val last = view.rows().last()
            assertEquals("Spotify lists unavailable", last.label); assertFalse(last.selectable); assertEquals("(playlists + recent failed)", last.sub)
            view.cur = view.rows().lastIndex; text(view)
        } finally { ui.close() }
    }

    @Test fun noActiveSpotifyDeviceOpensPickerThatStartsThePlaylistOnTheChosenDevice() {
        val started = mutableListOf<Pair<String, String>>()
        var deviceList = listOf(Device("desk", "Desktop"), Device("tv", "Living room TV"))
        val media = object : MediaRepository by fake.media {
            override val nowPlaying: NowPlaying? get() = null
            override val playlists get() = listOf(Playlist("spotify:playlist:p", "Morning"))
            override val devices get() = deviceList
            override suspend fun play(source: String, id: String) = ActionResult(false, "Choose a Spotify device", SPOTIFY_CHOOSE_DEVICE)
            override suspend fun playOn(uri: String, deviceId: String): ActionResult { started += uri to deviceId; return ActionResult() }
        }
        val ui = ui(media)
        try {
            ui.push(Spotify(ui))
            ui.top.key("sel")
            val picker = ui.top as MediaDevices
            assertEquals("spotify:playlist:p", picker.playUri)
            assertEquals(listOf("Desktop", "Living room TV"), picker.rows().map { it.label })
            picker.cur = 1; picker.key("sel")
            assertEquals(listOf("spotify:playlist:p" to "tv"), started)
            assertTrue(ui.top is NowPlayingScreen)
            deviceList = emptyList()
            val empty = MediaDevices(ui, playUri = "spotify:playlist:p")
            assertEquals(listOf("No Spotify devices"), empty.rows().map { it.label }); assertFalse(empty.rows()[0].selectable)
            assertTrue(MediaDevices(ui).rows().isEmpty()) // The X-menu output list is unchanged.
        } finally { ui.close() }
    }

    @Test fun audiobooksShowFiveRowsWithoutDanglingSeparatorAndUnderOnePercent() {
        val bookList = (1..7).map { Book("b$it", "Book $it", "Author $it", if (it == 2) "" else "Chapter $it", it / 10.0) }.toMutableList()
        bookList[3] = bookList[3].copy(progress = .001)
        val media = object : MediaRepository by fake.media { override val books get() = bookList }
        val ui = ui(media)
        try {
            val view = Audiobooks(ui)
            assertEquals(AUDIOBOOK_ROWS, view.rows().size); assertEquals(5, AUDIOBOOK_ROWS)
            assertEquals(listOf("Book 1", "Book 2", "Book 3", "Book 4", "Book 5"), view.rows().map { it.label })
            assertEquals("Author 2", bookSubtitle(bookList[1])); assertEquals("Author 1 · Chapter 1", bookSubtitle(bookList[0]))
            assertEquals(listOf("10%", "20%", "30%", "<1%", "50%"), view.rows().map { it.right })
            assertEquals("0%", bookPercent(0.0)); assertEquals("1%", bookPercent(.006)); assertEquals("100%", bookPercent(1.0))
            text(view)
        } finally { ui.close() }
    }

    @Test fun absSyncLedgerStopsPausedRepostsAndNeverCarriesSecondsToAnotherSession() {
        val ledger = AbsSyncLedger()
        assertTrue(ledger.shouldSync("s1", 100.0, 0.0)) // First report for a session.
        ledger.delivered("s1", 100.0)
        assertFalse(ledger.shouldSync("s1", 100.0, 0.0)) // Paused: nothing new to say.
        assertFalse(ledger.shouldSync("s1", 100.3, 0.0))
        assertTrue(ledger.shouldSync("s1", 70.0, 0.0)) // A seek while paused is reported.
        assertTrue(ledger.shouldSync("s1", 100.0, 30.0)) // Listening is always reported.
        assertTrue(ledger.shouldSync("s2", 100.0, 0.0)) // New session.
        assertEquals(30.0, ledger.carryOver("s1", "s1", 30.0), 0.0)
        assertEquals(0.0, ledger.carryOver("s1", "s2", 30.0), 0.0)
    }

    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }
    private val epoch = Instant.parse("2026-11-12T20:00:00Z").toEpochMilli()

    @Test fun liveStoreDecodesLibraryAndErrorsMaps409ToDevicePickerAndPlaysOnChosenDevice() = runTest {
        val f = FixtureGateway()
        val data = LiveDataStore(context(), backgroundScope, f.client, { epoch }, { testScheduler.currentTime / 1000.0 }, startBackground = false)
        try {
            data.media.refresh()
            assertEquals(listOf(Playlist("spotify:playlist:lib1", "Library playlist")), data.media.libraryPlaylists)
            assertTrue(data.media.spotifyErrors.isEmpty())
            assertEquals(listOf("Synthetic book"), data.media.books.map { it.title })
            f.fixtures["GET /spotify/home"] = gatewayJson.parseToJsonElement("""{"discoverWeekly":null,"liked":{"uri":"spotify:collection","name":"Liked Songs"},"current":null,"recent":[],"playlists":[],"errors":["playlists","recent"]}""")
            data.media.refresh()
            assertEquals(listOf("playlists", "recent"), data.media.spotifyErrors)
            f.status = 409
            val result = data.media.play("spotify", "spotify:playlist:abc")
            assertFalse(result.ok); assertEquals(SPOTIFY_CHOOSE_DEVICE, result.id); assertEquals("Choose a Spotify device", result.message)
            f.status = 200
            assertTrue(data.media.playOn("spotify:playlist:abc", "device1").ok)
            val body = gatewayJson.parseToJsonElement(f.sent.last { it.path == "/spotify/play" }.body)
            assertEquals("spotify:playlist:abc", body.s("uri")); assertEquals("device1", body.s("deviceId"))
            f.status = 502
            val failed = data.media.play("spotify", "spotify:playlist:abc")
            assertFalse(failed.ok); assertNotEquals(SPOTIFY_CHOOSE_DEVICE, failed.id)
        } finally { data.close() }
    }
}
