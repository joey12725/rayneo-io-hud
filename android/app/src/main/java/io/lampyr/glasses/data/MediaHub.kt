package io.lampyr.glasses.data

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.*
import android.os.SystemClock
import io.lampyr.glasses.input.MediaControl
import kotlinx.coroutines.*

/** Captures phone sessions into immutable UI snapshots; never queries the network from draw(). */
class MediaHub(private val context: Context, private val scope: CoroutineScope, private val changed: () -> Unit) : MediaControl {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val component = ComponentName(context, PhoneNotifications::class.java)
    private var controllers = emptyList<MediaController>()
    private var active: MediaController? = null
    var snapshot: NowPlaying? = null; private set
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() = refresh()
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
    private val ticker: Job
    init {
        runCatching { manager.addOnActiveSessionsChangedListener(sessions, component) }
        refresh()
        ticker = scope.launch { while (isActive) { delay(1000); refresh() } }
    }
    fun refresh() {
        val next = runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
        if (next.map { it.sessionToken } != controllers.map { it.sessionToken }) {
            controllers.forEach { it.unregisterCallback(callback) }; controllers = next
            controllers.forEach { it.registerCallback(callback) }
        }
        update()
    }
    private fun update() {
        active = controllers.filter { it.metadata != null && it.playbackState != null && it.playbackState?.state !in listOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR) }
            .sortedByDescending { it.playbackState?.lastPositionUpdateTime ?: 0 }
            .let { list -> list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull() }
        val c = active
        val state = c?.playbackState
        val meta = c?.metadata
        val previous = snapshot
        snapshot = if (c == null || state == null || meta == null) AbsPlayer.instance?.snapshot?.copy(volume = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 15.0 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)).toInt()) else {
            val playing = state.state == PlaybackState.STATE_PLAYING
            val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000.0
            val position = state.position / 1000.0 + if (playing) ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) / 1000.0 * state.playbackSpeed) else 0.0
            val source = when { c.packageName.contains("spotify") -> "spotify"; c.packageName.contains("youtube") -> "youtube"; else -> "abs" }
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            NowPlaying(source, meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_AUTHOR).orEmpty(),
                meta.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(), position.coerceIn(0.0, if (duration > 0) duration else position.coerceAtLeast(0.0)), duration, playing,
                "Phone", (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 15.0 / max).toInt(), state.playbackSpeed.toDouble().takeIf { it > 0 } ?: 1.0)
        }
        if (previous != snapshot) changed()
    }
    fun control(action: String, value: Double?): Boolean {
        val c = active
        if (c == null) {
            val local = AbsPlayer.instance?.takeIf { it.snapshot != null } ?: return false
            if (action == "pause" || (action == "toggle" && local.snapshot?.playing == true)) { local.pause(); return true }
            // Own-session access is possible even before notification listener permission is granted.
            return local.control(action, value)
        }
        val needed = when (action) {
            "toggle" -> if (snapshot?.playing == true) PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE else PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE
            "play" -> PlaybackState.ACTION_PLAY
            "pause" -> PlaybackState.ACTION_PAUSE
            "next" -> PlaybackState.ACTION_SKIP_TO_NEXT
            "previous" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
            "seek" -> PlaybackState.ACTION_SEEK_TO
            else -> 0L
        }
        if (needed != 0L && (c.playbackState?.actions ?: 0L) and needed == 0L) return false
        val t = c.transportControls
        when (action) {
            "toggle" -> if (snapshot?.playing == true) t.pause() else t.play()
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            "seek" -> t.seekTo(((snapshot?.positionSec ?: 0.0) + (value ?: 0.0)).coerceAtLeast(0.0).times(1000).toLong())
            "speed" -> {
                if ((c.playbackState?.actions ?: 0) and PlaybackState.ACTION_SET_PLAYBACK_SPEED == 0L) throw GatewayFailure("Playback speed unsupported")
                t.setPlaybackSpeed((value ?: 1.0).toFloat())
            }
            "volume" -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, (((value ?: 0.0).coerceIn(0.0, 15.0) / 15) * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0)
            else -> return false
        }
        update(); return true
    }
    override fun toggle() { control("toggle", null) }
    override fun direction(direction: String) {
        val value = snapshot ?: return
        when (direction) {
            "up", "down" -> control("volume", (value.volume + if (direction == "up") 1 else -1).toDouble())
            "left", "right" -> if (value.source == "spotify") control(if (direction == "right") "next" else "previous", null) else control("seek", if (direction == "right") 30.0 else -30.0)
        }
    }
    fun pauseForJoyCall(): () -> Unit {
        val playing = controllers.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val local = AbsPlayer.instance?.takeIf { it.snapshot?.playing == true && playing.none { c -> c.packageName == context.packageName } }
        playing.forEach { it.transportControls.pause() }; local?.pause()
        return {
            playing.filter { it.playbackState?.state == PlaybackState.STATE_PAUSED }.forEach { it.transportControls.play() }
            if (local != null && AbsPlayer.instance === local && local.snapshot?.playing == false) local.control("play", null)
        }
    }
    /** [pkg]'s active session (any state, including stopped), for starting playback without an activity. */
    fun session(pkg: String): MediaController? = controllers.firstOrNull { it.packageName == pkg }
    fun close() { ticker.cancel(); controllers.forEach { it.unregisterCallback(callback) }; runCatching { manager.removeOnActiveSessionsChangedListener(sessions) } }
}
