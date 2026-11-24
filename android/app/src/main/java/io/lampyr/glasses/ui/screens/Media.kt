package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import kotlin.math.*
import kotlinx.coroutines.launch

class NowPlayingScreen(ui: Ui) : Screen(ui, "np") {
    private var openingUntil = 0.0
    private var openingSession: NowPlaying? = null
    /** Shown instead of "Opening on phone…" when the phone needs the wearer (unlock, tap a notification). */
    internal var openingText: String? = null; private set
    fun openingOnPhone(previous: NowPlaying?, text: String? = null) {
        openingSession = previous; openingText = text; openingUntil = ui.nowS() + if (text == null) 10 else 30; ui.requestRender()
    }
    private val opening: Boolean get() {
        if (np?.source == "youtube" && np != openingSession) openingUntil = 0.0
        return ui.nowS() < openingUntil
    }
    private val np
        get() = ui.data.media.nowPlaying

    override val needsAnimation
        get() = opening || np?.playing == true

    override fun onOpen() {
        ui.refresh { ui.data.media.refresh() }
    }

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val m = np
            if (m == null || opening) {
                frame(ui, "Now playing", "media", t)
                if (opening && openingText != null) clampLines(openingText!!, CW - 12, 3).forEachIndexed { i, line -> drawLine(line, CX, TOP + i * lh(), "m") }
                else tx(if (opening) "Opening on phone…" else "Nothing playing", CX, TOP)
                return@with
            }
            frame(
                ui,
                "Now playing",
                if (m.source == "spotify") "media"
                else if (m.source == "youtube") "youtube" else "book",
                if (m.playing) t else 0.0,
                railText =
                    listOf(
                        when (m.source) {
                            "spotify" -> "Spotify"
                            "youtube" -> "YouTube"
                            else -> "Books"
                        },
                        if (m.playing) "" else "PAUSED",
                    ),
            )
            var y = TOP - 2
            clampLines(m.title, CW - 12, 2, "l").forEach {
                drawLine(it, CX, y, "l")
                y += lh("l")
            }
            tx(fit(m.subtitle, CW - 8), CX, y + 2, tone = SECONDARY)
            y += lh() + 6
            bar(CX, y, CW - 8, 12, if (m.durationSec > 0) m.positionSec / m.durationSec else 0.0)
            y += 18
            tx(m.elapsedLabel ?: fmtS(m.positionSec), CX, y, "s", tone = SECONDARY)
            txR(m.durationLabel ?: fmtS(m.durationSec), W - 16, y, "s", tone = SECONDARY)
            y += lh("s") + 4
            tx(
                (if (m.source == "spotify") "On " + m.device
                else "Speed " + number(m.speed) + "×") + "  ·  Vol " + m.volume,
                CX,
                y,
            )
            y += lh() + 6
            hintTextR("X change · " + if (m.source == "spotify") "output" else "speed", W - 14, BOTTOM - 16, "s")
        }

    override fun key(k: String): Boolean {
        if (k == "act") { ui.push(MediaMenu(ui)); return true }
        val m = np ?: return false
        when (k) {
            "sel" -> ui.perform { ui.data.media.control("toggle") }
            "left",
            "right" -> {
                val forward = k == "right"
                ui.perform(if (m.source != "spotify") if (forward) "+30 s" else "−30 s" else null) {
                    ui.data.media.control(
                        if (m.source == "spotify") if (forward) "next" else "previous" else "seek",
                        if (forward) 30.0 else -30.0,
                    )
                }
            }
            "up",
            "down" ->
                ui.perform {
                    ui.data.media.control(
                        "volume",
                        (m.volume + if (k == "up") 1 else -1).coerceIn(0, 15).toDouble(),
                    )
                }
            "act" -> ui.push(MediaMenu(ui))
            else -> return false
        }
        return true
    }

    override fun legend(): Map<String, String> {
        val m = np
        return mapOf(
            "sel" to if (m?.playing == true) "Pause" else "Play",
            "left" to if (m?.source == "spotify") "Previous track" else "Back 30 s",
            "right" to if (m?.source == "spotify") "Next track" else "Forward 30 s",
            "up" to "Volume up",
            "down" to "Volume down",
            "act" to "Change media / " + if (m?.source == "spotify") "output device" else "speed",
        )
    }

    override fun drawText(tg: TextGrid) {
        val m = np
        TextChrome.header(tg, "Now playing", if (m?.playing == true) when (m.source) { "spotify" -> "Spotify"; "youtube" -> "YouTube"; else -> "Audiobook" } else "PAUSED")
        if (m == null || opening) {
            if (opening && openingText != null) gridLines(openingText!!, tg.cols).take(3).forEachIndexed { i, line -> tg.put(0, 2 + i, line) }
            else tg.put(0, 2, if (opening) "Opening on phone..." else "Nothing playing")
            TextChrome.footer(tg, hint = "X menu B back"); return
        }
        val title = gridLines(m.title, tg.cols)
        val titleRows = min(title.size, if (tg.rows >= 7) 2 else 1)
        title.take(titleRows).forEachIndexed { i, line ->
            tg.put(0, i + 1, if (i == titleRows - 1 && title.size > titleRows) TextChrome.fit(line + "...", tg.cols) else line)
        }
        tg.put(0, titleRows + 1, TextChrome.fit(if (m.source == "spotify") m.artist.ifBlank { m.subtitle } else m.subtitle, tg.cols))
        val fraction = if (m.durationSec > 0) m.positionSec / m.durationSec else 0.0
        val times = "${m.elapsedLabel ?: fmtS(m.positionSec)} / ${m.durationLabel ?: fmtS(m.durationSec)}"
        if (tg.rows >= 7) {
            TextChrome.pair(tg, titleRows + 2, TextChrome.bar(fraction), "${(fraction * 100).toInt()}%")
            tg.put(0, titleRows + 3, TextChrome.fit(times, tg.cols))
            if (titleRows == 1) {
                TextChrome.pair(tg, 5, if (m.source == "spotify") m.device else "Speed ${number(m.speed)}x", "Vol ${m.volume}")
                TextChrome.footer(tg, hint = if (m.playing) "Y pause X menu <> skip" else "Y play X menu <> skip")
            } else TextChrome.footer(tg, hint = if (m.playing) "Y pause X menu <> skip" else "Y play X menu <> skip")
        } else {
            TextChrome.pair(tg, 3, TextChrome.bar(fraction, 6), "Vol ${m.volume}")
            TextChrome.footer(tg, hint = "Y play X menu <> skip")
        }
    }
}

class MediaMenu(ui: Ui) :
    ListView(
        ui,
        "mmenu",
        items = {
            val m = ui.data.media.nowPlaying
            listOf(
                if (m?.source == "spotify")
                    Row(
                        "Output device",
                        icon = "phone",
                        right = m.device,
                        open = { ui.push(MediaDevices(ui)) },
                    )
                else
                    Row(
                        "Playback speed",
                        icon = "gauge",
                        right = number(m?.speed ?: 1.0) + "×",
                        open = { ui.push(MediaSpeed(ui)) },
                    ),
                Row("CHANGE MEDIA", sep = true),
                Row("Audiobooks", icon = "book", open = { ui.push(Audiobooks(ui)) }),
                Row("Spotify", icon = "spotify", open = { ui.push(Spotify(ui)) }),
                Row("YouTube", icon = "youtube", open = { ui.push(YouTube(ui)) }),
            )
        },
        chrome = { t -> frame(ui, "Media", "media", t) },
    )

private fun play(ui: Ui, source: String, id: String) {
    val previous = ui.data.media.nowPlaying
    ui.perform(
        after = { result ->
            // No active Spotify device: let the wearer choose one, then start [id] there.
            if (result.id == SPOTIFY_CHOOSE_DEVICE) ui.push(MediaDevices(ui, playUri = id))
            else {
                val i = ui.stack.indexOfFirst { it.id == "np" }
                if (i >= 0) {
                    while (ui.stack.lastIndex > i) ui.pop()
                } else {
                    ui.goHome()
                    ui.push(NowPlayingScreen(ui))
                }
                if (source == "youtube") (ui.top as? NowPlayingScreen)?.openingOnPhone(previous, result.message)
            }
        }
    ) {
        // Starting an app on the phone can take seconds: keep its progress line ("Starting Spotify on phone…") on the glasses.
        val progress = ui.scope.launch {
            while (true) { ui.data.media.startStatus?.let(ui::flash); kotlinx.coroutines.delay(500) }
        }
        try { ui.data.media.play(source, id).let { if (!it.ok && it.id == SPOTIFY_CHOOSE_DEVICE) it.copy(ok = true) else it } }
        finally { progress.cancel() }
    }
}

/** Opens Now playing after a successful start from the device picker. */
private fun showNowPlaying(ui: Ui) {
    val i = ui.stack.indexOfFirst { it.id == "np" }
    if (i >= 0) { while (ui.stack.lastIndex > i) ui.pop() } else { ui.goHome(); ui.push(NowPlayingScreen(ui)) }
}

/** Audiobook row subtitle: no dangling separator when the chapter is unknown. */
internal fun bookSubtitle(b: Book) = listOf(b.author, b.chapter).filter { it.isNotBlank() }.joinToString(" · ")

/** Whole percent, but `<1%` for a started book below one percent. */
internal fun bookPercent(progress: Double) = if (progress > 0 && progress < .005) "<1%" else "${floor(progress * 100 + .5).toInt()}%"

/** Audiobooks shows this many recently played, unfinished books (the gateway sorts and filters). */
const val AUDIOBOOK_ROWS = 5

class Audiobooks(ui: Ui) :
    ListView(
        ui,
        "abs",
        items = {
            ui.data.media.books.take(AUDIOBOOK_ROWS).map { b ->
                Row(
                    b.title,
                    sub = "",
                    subLines = layout(bookSubtitle(b), CW - 60, "s")[0],
                    right = bookPercent(b.progress),
                    open = { play(ui, "abs", b.id) },
                )
            }
        },
        chrome = { t -> frame(ui, "Audiobooks", "book", t, railText = listOf("Recent")) },
    ) {
    init {
        refreshHook = { ui.data.media.refresh() }
    }
}

class Spotify(ui: Ui) :
    ListView(
        ui,
        "spot",
        items = {
            val playlists = ui.data.media.playlists
            val discover = playlists.find { it.name.equals("Discover Weekly", ignoreCase = true) }
            val liked = playlists.find { it.uri == "spotify:collection" || it.name.equals("Liked Songs", ignoreCase = true) }
            val recent = playlists.filter { it != discover && it != liked }
            fun playlist(p: Playlist, icon: String = "note") = Row(p.name, icon = icon,
                open = { play(ui, "spotify", p.uri) })
            buildList {
                discover?.let { add(playlist(it, "spotify")) }
                ui.data.media.nowPlaying?.takeIf { it.source == "spotify" }?.let { current ->
                    add(Row("Currently playing", icon = "media", sub = "",
                        subLines = layout(current.title + " · " + current.artist, CW - 60, "s")[0],
                        open = { ui.perform(after = { ui.push(NowPlayingScreen(ui)) }) { ui.data.media.control("play") } }))
                }
                liked?.let { add(playlist(it)) }
                if (recent.isNotEmpty()) add(Row("RECENT PLAYLISTS", sep = true))
                addAll(recent.map { playlist(it) })
                val shown = playlists.map { it.uri }.toSet()
                val library = ui.data.media.libraryPlaylists.filter { it.uri !in shown }
                if (library.isNotEmpty()) add(Row("PLAYLISTS", sep = true))
                addAll(library.map { playlist(it) })
                val errors = ui.data.media.spotifyErrors.filter { it == "playlists" || it == "recent" }
                if (errors.isNotEmpty()) add(Row("Spotify lists unavailable", sub = "(" + errors.joinToString(" + ") + " failed)", selectable = false))
            }
        },
        chrome = { t ->
            frame(
                ui,
                "Spotify",
                "spotify",
                t,
                railText = listOf("On " + ui.data.media.nowPlaying?.device.orEmpty()),
            )
        },
    ) {
    init {
        refreshHook = { ui.data.media.refresh() }
    }
}

/** Output device list. With [playUri] (no active Spotify device) choosing a device starts that URI there. */
class MediaDevices(ui: Ui, val playUri: String? = null) :
    ListView(
        ui,
        "dev",
        items = {
            ui.data.media.devices.map { d ->
                Row(
                    d.name,
                    right = if (d.active) "current" else "",
                    icon = if (d.name.contains("phone")) "phone" else "env",
                    open = {
                        if (playUri == null) ui.perform(after = { ui.pop() }) { ui.data.media.transfer(d.id) }
                        else ui.perform(after = { showNowPlaying(ui) }) { ui.data.media.playOn(playUri, d.id) }
                    },
                )
            }.ifEmpty {
                if (playUri == null) emptyList()
                else listOf(Row("No Spotify devices", sub = "(open Spotify on the phone)", selectable = false))
            }
        },
        chrome = { t ->
            frame(ui, "Output device", "phone", t, railText = listOf(if (playUri == null) "Spotify Connect" else "Play on"))
        },
    ) {
    init {
        if (playUri != null) refreshHook = { ui.data.media.refresh() }
    }
}

class MediaSpeed(ui: Ui) :
    ListView(
        ui,
        "speed",
        items = {
            listOf(1.0, 1.25, 1.5, 1.75, 2.0).map { speed ->
                Row(
                    number(speed) + "×",
                    right = if (ui.data.media.nowPlaying?.speed == speed) "current" else "",
                    open = {
                        ui.perform("Speed ${number(speed)}×", after = { ui.pop() }) {
                            ui.data.media.control("speed", speed)
                        }
                    },
                )
            }
        },
        chrome = { t -> frame(ui, "Playback speed", "gauge", t) },
    )

class YouTube(ui: Ui) : YouTubeList(ui)

/** The YouTube list the glasses show: the gateway's first list (YOUTUBE_LIST_NAME), "Picks" by default. */
fun youtubeList(ui: Ui) = ui.data.media.youtubeLists.firstOrNull() ?: io.lampyr.glasses.data.DEFAULT_YOUTUBE_LIST_NAME

open class YouTubeList(ui: Ui) :
    ListView(
        ui,
        "yt",
        hint = "Y play · B back",
        items = {
            ui.data.media.videos(youtubeList(ui)).take(25).map { v ->
                Row(
                    v.title,
                    sub = "",
                    subLines = layout(v.channel + " · " + fmtS(v.durationSec), CW - 60, "s")[0],
                    open = { play(ui, "youtube", v.videoId) },
                )
            }
        },
        chrome = { t -> frame(ui, youtubeList(ui), "youtube-outline", t) },
    ) {
    init {
        textTitle = { youtubeList(ui) }
        textWrapFocus = true
        textItems = { rows().mapIndexed { index, row -> row.copy(subLines = null, sub = null,
            right = ui.data.media.videos(youtubeList(ui)).getOrNull(index)?.durationSec?.let(::fmtS).orEmpty()) } }
        refreshHook = { ui.data.media.refreshVideos(youtubeList(ui)) }
    }
}
