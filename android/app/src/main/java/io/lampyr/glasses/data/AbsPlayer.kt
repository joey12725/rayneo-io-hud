package io.lampyr.glasses.data

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.media.session.*
import android.os.*
import io.lampyr.glasses.MainActivity
import io.lampyr.glasses.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Progress-sync bookkeeping for [AbsPlayer]. A paused player (no new listened seconds, same position, same
 * session) stops posting, and seconds from a failed post are only carried over while the same session is
 * still playing, so ABS never receives the same listened seconds twice or seconds of another book.
 */
internal class AbsSyncLedger {
    private var session = ""
    private var position = Double.NaN
    fun shouldSync(sessionId: String, positionSec: Double, listenedSec: Double): Boolean =
        listenedSec > 0.0 || sessionId != session || position.isNaN() || kotlin.math.abs(positionSec - position) >= .5
    fun delivered(sessionId: String, positionSec: Double) { session = sessionId; position = positionSec }
    /** Seconds to restore after a failed post: zero once another session has started. */
    fun carryOver(failedSession: String, currentSession: String, listenedSec: Double) = if (failedSession == currentSession) listenedSec else 0.0
}

/** Small foreground audiobook player with a phone MediaSession, including multi-file books. */
class AbsPlayer : Service() {
    private val ledger = AbsSyncLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private lateinit var gateway: GatewayClient
    private var player: MediaPlayer? = null
    private var prepared = false
    private var bookId = ""
    private var sessionId = ""
    private var title = "Audiobook"
    private var author = ""
    private var duration = 0.0
    private var offset = 0.0
    private var speed = 1f
    private var tracks: List<JsonElement> = emptyList()
    private var trackIndex = 0
    private var listenedMs = 0L
    private var tick = SystemClock.elapsedRealtime()
    private var ticker: Job? = null
    var snapshot: NowPlaying? = null; private set
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { if (it < 0) pause() }.build()
    }
    private fun position() = offset + if (prepared) (player?.currentPosition ?: 0) / 1000.0 else 0.0
    override fun onCreate() {
        super.onCreate(); instance = this
        gateway = AppSettings(this).let { GatewayClient(it.url, it.token) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("books", "Audiobooks", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "ABS").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() { pause(); stopSelf() }
                override fun onSeekTo(pos: Long) = seek(pos / 1000.0)
                override fun onFastForward() = seek(position() + 30)
                override fun onRewind() = seek(position() - 30)
                override fun onSetPlaybackSpeed(value: Float) { speed = value.coerceIn(.5f, 3f); if (prepared) player?.playbackParams = PlaybackParams().setSpeed(speed); publish() }
            })
            isActive = true
        }
        startForeground(2, notification())
        ticker = scope.launch {
            var count = 0
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                if (prepared && player?.isPlaying == true) listenedMs += now - tick
                tick = now; publish()
                if (++count % 30 == 0) sync()
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "pause" -> { wantPlaying = false; pause() }
            "play" -> { wantPlaying = true; resume() }
            "book" -> {
                sync()
                val row = gatewayJson.parseToJsonElement(intent.getStringExtra("data") ?: "{}").jsonObject
                bookId = intent.getStringExtra("id").orEmpty(); sessionId = row.s("sessionId"); title = row.s("title", "Audiobook"); author = row.s("author"); duration = row.n("durationSec")
                tracks = row.a("tracks").ifEmpty { listOf(obj("streamUrl" to str(row.s("streamUrl")), "offsetSec" to JsonPrimitive(0), "durationSec" to JsonPrimitive(duration))) }
                wantPlaying = true
                seek(row.n("startSec"))
            }
        }
        return START_NOT_STICKY // A killed player must not falsely resume or report progress.
    }
    /** What the listener asked for; survives async track preparation. */
    private var wantPlaying = false

    private fun openTrack(index: Int, at: Double) {
        player?.release(); prepared = false; trackIndex = index
        val track = tracks.getOrNull(index) ?: return
        offset = track.n("offsetSec")
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(track.s("streamUrl"))
            setOnPreparedListener {
                prepared = true; seekTo(((at - offset).coerceAtLeast(0.0) * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
                // Preparation is async: only start if the listener still wants audio (a pause or a
                // paused seek across a file boundary must not resume playback).
                if (wantPlaying) resume() else publish()
            }
            setOnCompletionListener { if (trackIndex < tracks.lastIndex) openTrack(trackIndex + 1, tracks[trackIndex + 1].n("offsetSec")) else pause() }
            setOnErrorListener { _, _, _ -> pause(); true }
            prepareAsync()
        }
        publish()
    }
    private fun seek(at: Double) {
        val bounded = at.coerceIn(0.0, if (duration > 0) duration else at.coerceAtLeast(0.0))
        val index = tracks.indexOfLast { it.n("offsetSec") <= bounded }.coerceAtLeast(0)
        if (player == null || index != trackIndex || !prepared) openTrack(index, bounded)
        else player?.seekTo(((bounded - offset) * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
        publish()
    }
    private fun resume() {
        if (!prepared) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        player?.start(); player?.playbackParams = PlaybackParams().setSpeed(speed); tick = SystemClock.elapsedRealtime(); publish()
    }
    fun control(action: String, value: Double?): Boolean {
        when (action) {
            "play", "toggle" -> if (action == "toggle" && snapshot?.playing == true) pause() else resume()
            "pause" -> pause()
            "seek" -> seek(position() + (value ?: 0.0))
            "speed" -> { speed = (value ?: 1.0).toFloat().coerceIn(.5f, 3f); if (prepared) player?.playbackParams = PlaybackParams().setSpeed(speed); publish() }
            "volume" -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, (((value ?: 0.0).coerceIn(0.0, 15.0) / 15) * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0)
            else -> return false
        }
        return true
    }
    fun pause() {
        if (prepared) { if (player?.isPlaying == true) listenedMs += (SystemClock.elapsedRealtime() - tick).coerceAtLeast(0); player?.pause() }
        tick = SystemClock.elapsedRealtime(); sync(); audio.abandonAudioFocusRequest(focus); publish()
    }
    private fun sync() {
        if (bookId.isBlank() || sessionId.isBlank()) return
        val id = bookId
        val sid = sessionId
        val at = position()
        val seconds = listenedMs / 1000.0
        if (!ledger.shouldSync(sid, at, seconds)) return
        val body = obj("sessionId" to str(sid), "currentTimeSec" to JsonPrimitive(at), "timeListenedSec" to JsonPrimitive(seconds))
        listenedMs = 0
        scope.launch {
            try { gateway.write("/abs/progress/${segment(id)}", body); ledger.delivered(sid, at) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { listenedMs += (ledger.carryOver(sid, sessionId, seconds) * 1000).toLong() }
        }
    }
    private fun publish() {
        if (!::session.isInitialized) return
        val playing = prepared && player?.isPlaying == true
        snapshot = NowPlaying("abs", title, author, positionSec = position(), durationSec = duration, playing = playing, device = "Phone", speed = speed.toDouble())
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_ARTIST, author).putLong(MediaMetadata.METADATA_KEY_DURATION, (duration * 1000).toLong()).build())
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_SET_PLAYBACK_SPEED)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, (position() * 1000).toLong(), speed).build())
        getSystemService(NotificationManager::class.java).notify(2, notification())
    }
    private fun notification(): android.app.Notification {
        val playing = prepared && player?.isPlaying == true
        val action = PendingIntent.getService(this, if (playing) 1 else 2, Intent(this, AbsPlayer::class.java).setAction(if (playing) "pause" else "play"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return android.app.Notification.Builder(this, "books").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title).setContentText(author).setContentIntent(open)
            .addAction(android.app.Notification.Action.Builder(null, if (playing) "Pause" else "Play", action).build())
            .setStyle(android.app.Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0)).setOngoing(playing).build()
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { ticker?.cancel(); pause(); player?.release(); session.release(); instance = null; scope.launch { delay(1000); scope.cancel() }; super.onDestroy() }
    companion object {
        var instance: AbsPlayer? = null; private set
        fun play(context: Context, id: String, data: JsonElement): ActionResult {
            val intent = Intent(context, AbsPlayer::class.java).setAction("book").putExtra("id", id).putExtra("data", data.toString())
            // An existing media service needs no new background FGS launch. Otherwise use the
            // companion FGS context; Android decides whether its current exemption permits it.
            return try {
                val existing = instance
                if (existing != null) existing.onStartCommand(intent, 0, 0)
                else (io.lampyr.glasses.service.GlassesService.instance ?: context).startForegroundService(intent)
                ActionResult()
            } catch (e: RuntimeException) {
                android.util.Log.e("AbsPlayer", "Playback service start rejected", e)
                ActionResult.failure("Open the phone app to start playback")
            }
        }
    }
}
