package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.*
import kotlinx.serialization.json.*

/**
 * Media: now playing, transport (play/pause/next/previous/rewind/forward/speed/volume), Spotify playlists and
 * Connect devices, Audiobookshelf books, YouTube (the gateway's video list). Anything that starts playback shows Now Playing,
 * as the media screens do.
 */
object MediaOps : OpProvider {
    private fun AgentCall.picks() = ui.data.media.youtubeLists.firstOrNull() ?: DEFAULT_YOUTUBE_LIST_NAME

    private fun nowJson(np: NowPlaying) = buildJsonObject {
        put("source", np.source); put("title", np.title); putText("artist", np.artist); putText("subtitle", np.subtitle)
        put("playing", np.playing)
        if (np.durationSec > 0) { put("positionSec", np.positionSec.toLong()); put("durationSec", np.durationSec.toLong()) }
        putText("device", np.device)
        put("volume", np.volume)
        if (np.source != "spotify") put("speed", np.speed)
    }

    /** Starting something shows Now Playing (skipped while locked: the display is blank anyway). */
    private fun AgentCall.started(previous: NowPlaying?, youtube: Boolean = false, message: String? = null) {
        if (ui.locked) return
        val np = showNowPlaying(ui)
        if (youtube) np?.openingOnPhone(previous, message)
    }

    /** Every Spotify list the Spotify screen shows (Discover Weekly, Liked Songs, recent and library playlists). */
    private fun spotifyLists(media: MediaRepository) = (media.playlists + media.libraryPlaylists).distinctBy { it.uri }

    private fun AgentCall.device(): Device =
        resolve(str("device"), ui.data.media.devices, "Spotify device", "media.list_devices", { it.id }, { it.name })

    private fun devicesJson(devices: List<Device>) = JsonArray(devices.take(MAX_ITEMS).map {
        buildJsonObject { put("id", it.id); put("name", it.name); putText("type", it.type); if (it.active) put("active", true) }
    })

    private val SECONDS = Param.integer("seconds", "Seconds (default 30)", min = 1, max = 3600)

    private fun transport(name: String, title: String, description: String, action: String, message: String, confirm: Confirm = Confirm.POLICY) =
        OpSpec(name, title, description, risk = Risk.ACT, confirm = confirm) {
            done(ui.data.media.control(action), message)
        }

    override fun ops() = listOf(
        OpSpec(
            "media.now_playing", "Read now playing",
            "What is playing on the user's phone or Spotify: source (spotify, abs audiobook, youtube, phone), title, artist, playing, position, device, volume and speed.",
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            ui.data.media.refresh()
            val np = ui.data.media.nowPlaying
            ok(if (np == null) "Nothing is playing" else null, np?.let(::nowJson) ?: JsonNull)
        },

        transport("media.play", "Resume playback", "Resume whatever was playing (Spotify, an audiobook or the phone's media app).", "play", "Playing"),
        transport("media.pause", "Pause playback", "Pause playback.", "pause", "Paused", Confirm.NEVER),
        transport("media.next", "Next track", "Skip to the next track (Spotify or the phone's media app).", "next", "Next track"),
        transport("media.previous", "Previous track", "Go back to the previous track.", "previous", "Previous track"),

        OpSpec(
            "media.rewind", "Rewind", "Jump back in an audiobook or video (default 30 s, like the remote).",
            params = listOf(SECONDS), risk = Risk.ACT,
        ) { done(ui.data.media.control("seek", -int("seconds", 30).toDouble()), "Back ${int("seconds", 30)} s") },

        OpSpec(
            "media.forward", "Skip forward", "Jump forward in an audiobook or video (default 30 s, like the remote).",
            params = listOf(SECONDS), risk = Risk.ACT,
        ) { done(ui.data.media.control("seek", int("seconds", 30).toDouble()), "Forward ${int("seconds", 30)} s") },

        OpSpec(
            "media.set_speed", "Set playback speed",
            "Playback speed for audiobooks and videos (the glasses offer 1, 1.25, 1.5, 1.75 and 2). Not for Spotify.",
            params = listOf(Param.number("speed", "Speed, 0.5 to 3", required = true, min = 0.5, max = 3.0)),
            risk = Risk.ACT, tool = false,
            available = { ui -> if (ui.data.media.nowPlaying?.source == "spotify") "Spotify has no playback speed" else null },
        ) {
            val speed = num("speed")
            done(ui.data.media.control("speed", speed), "Speed ${speed.toString().removeSuffix(".0")}×")
        },

        OpSpec(
            "media.set_volume", "Set media volume", "Media volume, 0 to 15 (the remote's R2 + up/down steps). Spotify Connect devices use their own volume.",
            params = listOf(Param.integer("level", "0 to 15", required = true, min = 0, max = 15)),
            risk = Risk.ACT, tool = false,
        ) { done(ui.data.media.control("volume", int("level").toDouble()), "Volume ${int("level")}") },

        OpSpec(
            "media.list_spotify", "List Spotify playlists",
            "The Spotify lists the glasses offer: Discover Weekly, Liked Songs, recent playlists and the library, with uris for media_play_spotify.",
            risk = Risk.READ, tool = false, timeoutMs = 15_000,
        ) {
            ui.data.media.refresh()
            val media = ui.data.media
            ok(data = buildJsonObject {
                put("playlists", JsonArray(spotifyLists(media).take(40).map { buildJsonObject { put("name", it.name); put("uri", it.uri) } }))
                if (media.spotifyErrors.isNotEmpty()) put("unavailable", JsonArray(media.spotifyErrors.map(::JsonPrimitive)))
            })
        },

        OpSpec(
            "media.play_spotify", "Play on Spotify",
            "Play a Spotify list: liked=true for Liked Songs, playlist by name or uri, or query to match the user's playlists by name (catalog search is not available). device picks the Connect device.",
            params = listOf(
                Param.string("playlist", "Playlist name or spotify: uri (e.g. Discover Weekly)", maxLength = 300),
                Param.boolean("liked", "Play Liked Songs"),
                Param.string("query", "Words to match against the user's playlist names", maxLength = 200),
                Param.string("device", "Spotify Connect device id or name (see media.list_devices)", maxLength = 200),
            ),
            // A cold start waits up to 15 s for Spotify to register the phone, plus gateway round trips.
            risk = Risk.ACT, timeoutMs = 40_000,
        ) {
            val media = ui.data.media
            if (media.playlists.isEmpty()) media.refresh()
            val lists = spotifyLists(media)
            val wanted = strOrNull("playlist")
            val playlist: Playlist = when {
                bool("liked") -> lists.find { it.uri == "spotify:collection" || it.uri.endsWith(":collection") || it.name.equals("Liked Songs", true) }
                    ?: fail(Codes.UNAVAILABLE, "Liked Songs is not in the user's Spotify lists right now")
                wanted != null && wanted.startsWith("spotify:") -> lists.find { it.uri == wanted } ?: Playlist(wanted, wanted)
                wanted != null -> resolve(wanted, lists, "Spotify playlist", "media.list_spotify", { it.uri }, { it.name })
                strOrNull("query") != null -> {
                    val words = str("query").lowercase().split(Regex("\\W+")).filter { it.length > 1 }
                    val scored = lists.map { p -> p to words.count { p.name.lowercase().contains(it) } }.filter { it.second > 0 }
                    val best = scored.maxOfOrNull { it.second } ?: fail(Codes.FAILED, "None of the user's Spotify playlists match \"${str("query")}\" (catalog search is not available)",
                        JsonArray(lists.take(15).map { JsonPrimitive(it.name) }))
                    val top = scored.filter { it.second == best }.map { it.first }
                    top.singleOrNull() ?: fail(Codes.AMBIGUOUS, "Several playlists match \"${str("query")}\"",
                        JsonArray(top.take(8).map { buildJsonObject { put("id", it.uri); put("name", it.name) } }))
                }
                else -> fail(Codes.INVALID_ARGS, "Pass liked, playlist or query")
            }
            val previous = media.nowPlaying
            if (has("device") && media.devices.isEmpty()) media.refresh()
            val result = if (has("device")) media.playOn(playlist.uri, device().id) else media.play("spotify", playlist.uri)
            if (result.id == SPOTIFY_CHOOSE_DEVICE) {
                fail(Codes.UNAVAILABLE, "No Spotify device is active; call again with device (one of the listed)", devicesJson(media.devices))
            }
            if (result.ok) started(previous)
            done(result, "Playing ${playlist.name}")
        },

        OpSpec(
            "media.list_devices", "List Spotify devices", "Spotify Connect devices (id, name, active) for media.transfer and media_play_spotify.",
            risk = Risk.READ, tool = false, timeoutMs = 15_000,
        ) {
            ui.data.media.refresh()
            ok(data = devicesJson(ui.data.media.devices))
        },

        OpSpec(
            "media.transfer", "Move Spotify playback", "Move Spotify playback to another Connect device (id or name).",
            params = listOf(Param.string("device", "Device id or name (see media.list_devices)", required = true, maxLength = 200)),
            risk = Risk.ACT, tool = false, timeoutMs = 15_000,
        ) {
            if (ui.data.media.devices.isEmpty()) ui.data.media.refresh()
            val device = device()
            done(ui.data.media.transfer(device.id), "Moved to ${device.name}")
        },

        OpSpec(
            "media.list_audiobooks", "List audiobooks", "The user's recently played, unfinished audiobooks (Audiobookshelf): id, title, author, chapter, progress.",
            risk = Risk.READ, tool = false, timeoutMs = 15_000,
        ) {
            ui.data.media.refresh()
            ok(data = JsonArray(ui.data.media.books.take(MAX_ITEMS).map { b ->
                buildJsonObject {
                    put("id", b.id); put("title", b.title); putText("author", b.author); putText("chapter", b.chapter)
                    put("progressPercent", (b.progress * 100).toInt())
                }
            }))
        },

        OpSpec(
            "media.play_audiobook", "Play an audiobook",
            "Play an audiobook by title or id (see media.list_audiobooks) from where the user left off. Without book, resumes the current or most recent book.",
            params = listOf(Param.string("book", "Book title or id (optional)", maxLength = 300)),
            risk = Risk.ACT, timeoutMs = 20_000,
        ) {
            val media = ui.data.media
            if (media.books.isEmpty()) media.refresh()
            val previous = media.nowPlaying
            val wanted = strOrNull("book")
            if (wanted == null && previous?.source == "abs" && previous.title.isNotBlank()) {
                val r = media.control("play")
                if (r.ok) started(previous)
                return@OpSpec done(r, "Resumed ${previous.title}")
            }
            val book = if (wanted == null) media.books.firstOrNull() ?: fail(Codes.UNAVAILABLE, "No audiobook in progress")
                else resolve(wanted, media.books, "audiobook", "media.list_audiobooks", { it.id }, { it.title })
            val result = media.play("abs", book.id)
            if (result.ok) started(previous)
            done(result, "Playing ${book.title}")
        },

        OpSpec(
            "media.list_youtube", "List YouTube videos",
            "Videos in the YouTube list for media_play_youtube: id, title, channel, duration.",
            params = listOf(Param.integer("limit", "How many (default 10)", min = 1, max = MAX_ITEMS)),
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            ui.data.media.refreshVideos(picks())
            ok(data = JsonArray(ui.data.media.videos(picks()).take(int("limit", 10)).map { v ->
                buildJsonObject { put("id", v.videoId); put("title", v.title); put("channel", v.channel); put("durationSec", v.durationSec) }
            }))
        },

        OpSpec(
            "media.play_youtube", "Play a YouTube video",
            "Play a video from the YouTube list (id or title from media_list_youtube) on the phone; Now Playing shows it.",
            params = listOf(Param.string("video", "Video id or title", required = true, maxLength = 300)),
            risk = Risk.ACT, timeoutMs = 20_000,
        ) {
            val media = ui.data.media
            if (media.videos(picks()).isEmpty()) media.refreshVideos(picks())
            val wanted = str("video")
            val video = media.videos(picks()).find { it.videoId == wanted }
                ?: media.youtubeLists.flatMap { media.videos(it) }.find { it.videoId == wanted }
                ?: resolve(wanted, media.videos(picks()), "video", "media_list_youtube", { it.videoId }, { it.title })
            val previous = media.nowPlaying
            val result = media.play("youtube", video.videoId)
            if (result.ok) started(previous, youtube = true, message = result.message)
            done(result, "Playing ${video.title}")
        },
    )
}
