package io.lampyr.glasses.agent

import io.lampyr.glasses.agent.ops.MediaOps
import io.lampyr.glasses.ui.screens.NowPlayingScreen
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MediaOps on FakeDataStore (Project Hail Mary playing on the Living room TV). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaOpsTest {
    private val ActionRegistryTest.Harness.np get() = ui.data.media.nowPlaying!!

    @Test fun transportControls(): Unit = ActionRegistryTest.Harness(listOf(MediaOps)).use { h ->
        val now = h.call("media.now_playing").data!!.jsonObject
        assertEquals("abs", now["source"]!!.jsonPrimitive.content)
        assertEquals("Project Hail Mary", now["title"]!!.jsonPrimitive.content)
        assertTrue(h.call("media.pause").ok); assertFalse(h.np.playing)
        // Pausing never asks, even from outside the glasses conversation (it is not sensitive anyway).
        assertFalse(h.registry.needsConfirm(h.registry.ops["media.pause"]!!, Origin.OTHER))
        assertTrue(h.call("media.play").ok); assertTrue(h.np.playing)
        assertEquals("Next track", h.call("media.next").message)
        assertEquals("Previous track", h.call("media.previous").message)
        val at = h.np.positionSec
        assertTrue(h.call("media.forward", mapOf("seconds" to 120)).ok)
        assertEquals(at + 120, h.np.positionSec, 0.01)
        assertTrue(h.call("media.rewind").ok)
        assertEquals(at + 90, h.np.positionSec, 0.01)
        assertTrue(h.call("media.set_speed", mapOf("speed" to 1.25)).ok)
        assertEquals(1.25, h.np.speed, 0.0)
        assertEquals(Codes.INVALID_ARGS, h.call("media.set_speed", mapOf("speed" to 4)).code)
        assertTrue(h.call("media.set_volume", mapOf("level" to 9)).ok)
        assertEquals(9, h.np.volume)
    }

    @Test fun audiobooksResumeAndPlayAndShowNowPlaying(): Unit = ActionRegistryTest.Harness(listOf(MediaOps)).use { h ->
        val books = h.call("media.list_audiobooks").data!!.jsonArray
        assertEquals(h.ui.data.media.books.size, books.size)
        h.call("media.pause")
        val resumed = h.call("media.play_audiobook")
        assertTrue(resumed.message, resumed.ok)
        assertTrue(h.np.playing)
        assertTrue(h.ui.top is NowPlayingScreen)
        h.ui.goHome()
        val played = h.call("media.play_audiobook", mapOf("book" to "cibola"))
        assertTrue(played.message, played.ok)
        assertEquals("Cibola Burn", h.np.title)
        assertTrue(h.ui.top is NowPlayingScreen)
        assertEquals(Codes.FAILED, h.call("media.play_audiobook", mapOf("book" to "no such book")).code)
    }

    @Test fun spotifyPlaylistsLikedQueryAndDevices(): Unit = ActionRegistryTest.Harness(listOf(MediaOps)).use { h ->
        val lists = h.call("media.list_spotify").data!!.jsonObject["playlists"]!!.jsonArray
        assertTrue(lists.any { it.jsonObject["name"]!!.jsonPrimitive.content == "Liked Songs" })
        assertTrue(h.call("media.play_spotify", mapOf("liked" to true)).ok)
        assertEquals("spotify", h.np.source)
        assertTrue(h.np.subtitle.endsWith("Liked Songs"))
        assertTrue(h.ui.top is NowPlayingScreen)
        assertTrue(h.call("media.play_spotify", mapOf("playlist" to "deep work")).ok)
        assertTrue(h.np.subtitle.endsWith("Deep Work"))
        assertTrue(h.call("media.play_spotify", mapOf("query" to "something for a road trip")).ok)
        assertTrue(h.np.subtitle.endsWith("Road Trip"))
        assertEquals(Codes.FAILED, h.call("media.play_spotify", mapOf("query" to "zzzz qqqq")).code)
        assertEquals(Codes.INVALID_ARGS, h.call("media.play_spotify").code)
        assertEquals(Codes.UNAVAILABLE, h.call("media.set_speed", mapOf("speed" to 1.5)).code) // Spotify has no speed

        val devices = h.call("media.list_devices").data!!.jsonArray
        assertTrue(devices.size >= 2)
        assertTrue(h.call("media.transfer", mapOf("device" to "desktop")).ok)
        assertEquals("Desktop", h.np.device)
        assertTrue(h.call("media.play_spotify", mapOf("playlist" to "Weekend Session", "device" to "Laptop")).ok)
    }

    @Test fun youtubePicksListAndPlay(): Unit = ActionRegistryTest.Harness(listOf(MediaOps)).use { h ->
        val picks = h.call("media.list_youtube", mapOf("limit" to 3)).data!!.jsonArray
        assertEquals(3, picks.size)
        val id = picks[1].jsonObject["id"]!!.jsonPrimitive.content
        assertTrue(h.call("media.play_youtube", mapOf("video" to id)).ok)
        assertEquals("youtube", h.np.source)
        assertTrue(h.ui.top is NowPlayingScreen)
        assertTrue(h.call("media.play_youtube", mapOf("video" to "synth history")).ok)
        assertEquals("Synth history in 12 minutes", h.np.title)
        // Locked: playback still starts, but the screen is left alone.
        h.ui.goHome(); h.ui.locked = true
        assertTrue(h.call("media.play_youtube", mapOf("video" to id)).ok)
        assertFalse(h.ui.top is NowPlayingScreen)
    }
}
