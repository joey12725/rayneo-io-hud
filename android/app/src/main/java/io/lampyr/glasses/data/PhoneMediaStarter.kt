package io.lampyr.glasses.data

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import io.lampyr.glasses.debug.Trace
import kotlinx.coroutines.delay

const val SPOTIFY_PACKAGE = "com.spotify.music"
const val YOUTUBE_PACKAGE = "com.google.android.youtube"

enum class AppStatus { READY, SUSPENDED, MISSING }

/** What starting media on the phone needs from Android; [AndroidPhoneApps] is the real one, tests use a fake. */
interface PhoneApps {
    fun status(pkg: String): AppStatus
    fun locked(): Boolean
    /** Background-safe: binds Spotify's MediaBrowserService, which starts its process without any UI. */
    fun wakeSpotify(): Boolean
    /** Opens [uri] in the Spotify app (an activity start; the accessibility binding allows it from the background). */
    fun openSpotify(uri: String): Boolean
    fun releaseSpotify() {}
    fun openYouTube(videoId: String): Boolean
    /** True while [pkg]'s own media session on the phone reports STATE_PLAYING (read through the notification listener). */
    fun playing(pkg: String): Boolean = false
    /**
     * Background-safe start without any activity: asks [pkg]'s media session (Spotify's browser session or YouTube's
     * active session) to play [uri] via ACTION_PLAY_FROM_URI. False when no session offers it.
     */
    fun playFromUri(pkg: String, uri: String): Boolean = false
    fun youtubePlaying(): Boolean = playing(YOUTUBE_PACKAGE)
    /** Fallback when YouTube did not come up: a notification (full-screen when allowed) that opens the video. */
    fun notifyYouTube(videoId: String): Boolean
    /** Removes an earlier [notifyYouTube] notification (a new pick replaces it; a playing video no longer needs it). */
    fun clearYouTubeNotice() {}
}

/**
 * Starts Spotify or YouTube playback on the phone from the background (glasses in use, phone often asleep).
 * Pure timing logic over [PhoneApps]; [status] carries the progress line the glasses show while it runs.
 */
class PhoneMediaStarter(
    private val apps: PhoneApps,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val model: String = Build.MODEL.orEmpty(),
    /** Monotonic milliseconds: bounds the Spotify wait in real time, so slow gateway polls cannot stretch it. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile var status: String? = null; private set

    /**
     * Spotify answered 409 (no active device): start Spotify on the phone, wait for it to register as a Connect device,
     * then play [uri] there. [devices] reads `/spotify/devices`; [play] posts `/spotify/play` with a device id.
     */
    suspend fun startSpotify(uri: String, devices: suspend () -> List<Device>, play: suspend (String) -> Unit): ActionResult {
        when (apps.status(SPOTIFY_PACKAGE)) {
            AppStatus.MISSING -> return ActionResult(false, "Choose a Spotify device", SPOTIFY_CHOOSE_DEVICE)
            AppStatus.SUSPENDED -> return ActionResult.failure(SPOTIFY_PAUSED)
            AppStatus.READY -> Unit
        }
        try {
            status = STARTING_SPOTIFY
            Trace.log("Media", "Spotify cold start")
            var opened = false
            var askedLocal = false
            if (!apps.wakeSpotify()) { opened = true; apps.openSpotify(uri) }
            val started = now()
            while (true) {
                val waited = now() - started
                if (waited > SPOTIFY_WAIT_MS) break
                // Started straight through Spotify's own session: no Web API play, which would restart the list.
                if (askedLocal && apps.playing(SPOTIFY_PACKAGE)) { Trace.log("Media", "Spotify cold start played locally"); return ActionResult() }
                val phone = runCatching { devices() }.getOrDefault(emptyList()).let(::phoneDevice)
                if (phone != null) return playWithRetry(phone.id, play)
                if (!askedLocal && apps.playFromUri(SPOTIFY_PACKAGE, uri)) askedLocal = true
                if (!opened && waited >= SPOTIFY_OPEN_AFTER_MS) { opened = true; apps.openSpotify(uri) }
                pause(POLL_MS)
            }
            if (askedLocal && apps.playing(SPOTIFY_PACKAGE)) return ActionResult()
            Trace.log("Media", "Spotify cold start timed out")
            return if (apps.locked()) ActionResult.failure(UNLOCK_FOR_SPOTIFY)
            else ActionResult(false, "Spotify did not start on the phone", SPOTIFY_CHOOSE_DEVICE)
        } finally { status = null; apps.releaseSpotify() }
    }

    private suspend fun playWithRetry(deviceId: String, play: suspend (String) -> Unit): ActionResult {
        // A freshly registered device can briefly reject commands; one retry covers that window.
        try { play(deviceId) } catch (e: GatewayFailure) {
            if (e.code == 400) throw e
            pause(POLL_MS); play(deviceId)
        }
        Trace.log("Media", "Spotify cold start played")
        return ActionResult()
    }

    /** The phone's own Connect device: a smartphone, preferring this phone's model name. */
    internal fun phoneDevice(list: List<Device>): Device? {
        val phones = list.filter { it.type.equals("smartphone", ignoreCase = true) }
        return phones.firstOrNull { model.isNotBlank() && it.name.contains(model, ignoreCase = true) } ?: phones.singleOrNull()
    }

    /**
     * Opens [videoId] in YouTube and waits for its session to play. A locked phone keeps the activity paused behind the
     * keyguard, so YouTube's own session is asked to play the URI (background-safe); failing that, the wearer is asked
     * to unlock, and a start Android silently blocked falls back to a notification.
     */
    suspend fun startYouTube(videoId: String): ActionResult {
        when (apps.status(YOUTUBE_PACKAGE)) {
            AppStatus.SUSPENDED -> return ActionResult.failure(YOUTUBE_PAUSED)
            AppStatus.MISSING, AppStatus.READY -> Unit
        }
        try {
            status = OPENING_YOUTUBE
            apps.clearYouTubeNotice()
            val locked = apps.locked()
            val watch = watchUrl(videoId)
            if (!apps.openYouTube(videoId)) {
                Trace.log("Media", "YouTube start refused; notification fallback")
                if (locked && sessionStart(watch)) return ActionResult()
                apps.notifyYouTube(videoId)
                return ActionResult(true, if (locked) UNLOCK_FOR_YOUTUBE else TAP_NOTIFICATION)
            }
            if (locked) {
                Trace.log("Media", "YouTube opened behind lock screen")
                if (sessionStart(watch)) return ActionResult()
                apps.notifyYouTube(videoId)
                return ActionResult(true, UNLOCK_FOR_YOUTUBE)
            }
            var waited = 0L
            var nudged = false
            while (waited < YOUTUBE_WAIT_MS) {
                if (apps.youtubePlaying()) return ActionResult()
                // Opened but idle (e.g. the watch page waiting on a tap): ask its session once.
                if (!nudged && waited >= YOUTUBE_NUDGE_AFTER_MS) { nudged = true; apps.playFromUri(YOUTUBE_PACKAGE, watch) }
                pause(POLL_MS); waited += POLL_MS
            }
            if (apps.youtubePlaying()) return ActionResult()
            if (apps.locked()) { apps.notifyYouTube(videoId); return ActionResult(true, UNLOCK_FOR_YOUTUBE) }
            Trace.log("Media", "YouTube did not start; notification fallback")
            apps.notifyYouTube(videoId)
            return ActionResult(true, TAP_NOTIFICATION)
        } finally { status = null }
    }

    /** Asks YouTube's session to play [watch] and waits briefly for it; true once it plays. */
    private suspend fun sessionStart(watch: String): Boolean {
        if (!apps.playFromUri(YOUTUBE_PACKAGE, watch)) return false
        var waited = 0L
        while (waited < YOUTUBE_SESSION_WAIT_MS) {
            pause(POLL_MS); waited += POLL_MS
            if (apps.youtubePlaying()) { Trace.log("Media", "YouTube started through its session"); return true }
        }
        return false
    }

    companion object {
        const val POLL_MS = 1500L
        const val SPOTIFY_WAIT_MS = 15_000L
        const val SPOTIFY_OPEN_AFTER_MS = 4500L
        const val YOUTUBE_WAIT_MS = 9000L
        const val YOUTUBE_NUDGE_AFTER_MS = 4500L
        const val YOUTUBE_SESSION_WAIT_MS = 4500L
        fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=" + java.net.URLEncoder.encode(videoId, "UTF-8")
        const val STARTING_SPOTIFY = "Starting Spotify on phone…"
        const val OPENING_YOUTUBE = "Opening YouTube on phone…"
        const val SPOTIFY_PAUSED = "Spotify is paused on the phone (Focus mode)"
        const val YOUTUBE_PAUSED = "YouTube is paused on the phone (Focus mode)"
        const val UNLOCK_FOR_SPOTIFY = "Unlock the phone to start Spotify"
        const val UNLOCK_FOR_YOUTUBE = "Unlock the phone to play"
        const val TAP_NOTIFICATION = "Tap the YouTube notification on the phone"
    }
}

class AndroidPhoneApps(private val context: Context, private val hub: () -> MediaHub?) : PhoneApps {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var browser: MediaBrowser? = null
    @Volatile private var spotifySession: MediaController? = null

    override fun status(pkg: String): AppStatus = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        if (info.flags and ApplicationInfo.FLAG_SUSPENDED != 0) AppStatus.SUSPENDED else AppStatus.READY
    } catch (_: PackageManager.NameNotFoundException) { AppStatus.MISSING }

    override fun locked() = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun wakeSpotify(): Boolean {
        val service = runCatching {
            context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(SPOTIFY_PACKAGE), 0).firstOrNull()?.serviceInfo
        }.getOrNull() ?: return false
        // Spotify may refuse an unknown browser client, but binding has already started its process and Connect.
        main.post {
            runCatching {
                browser?.disconnect()
                spotifySession = null
                lateinit var created: MediaBrowser
                created = MediaBrowser(context, ComponentName(service.packageName, service.name), object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { spotifySession = runCatching { MediaController(context, created.sessionToken) }.getOrNull() }
                }, null)
                browser = created.also { it.connect() }
            }.onFailure { Trace.log("Media", "Spotify browser bind failed") }
        }
        return true
    }

    override fun releaseSpotify() { main.post { runCatching { browser?.disconnect() }; browser = null; spotifySession = null } }

    /** The session to drive [pkg] with: Spotify's browser-connected one first, else the notification listener's. */
    private fun session(pkg: String): MediaController? =
        (if (pkg == SPOTIFY_PACKAGE) spotifySession else null) ?: hub()?.let { it.refresh(); it.session(pkg) }

    override fun playing(pkg: String) = session(pkg)?.playbackState?.state == PlaybackState.STATE_PLAYING

    override fun playFromUri(pkg: String, uri: String): Boolean = runCatching {
        val c = session(pkg) ?: return false
        if ((c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_URI == 0L) return false
        c.transportControls.playFromUri(Uri.parse(uri), null)
        Trace.log("Media", "playFromUri $pkg"); true
    }.getOrDefault(false)

    override fun openSpotify(uri: String): Boolean {
        val deepLink = Regex("^spotify:(playlist|album|track):[A-Za-z0-9]+$").matches(uri)
        val intent = if (deepLink) Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(SPOTIFY_PACKAGE)
            else context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) ?: return false
        return start(intent)
    }

    override fun openYouTube(videoId: String): Boolean {
        if (start(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")))) return true
        return start(Intent(Intent.ACTION_VIEW, watchUri(videoId)))
    }

    override fun clearYouTubeNotice() { runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) } }

    override fun notifyYouTube(videoId: String): Boolean = runCatching {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Start media from glasses", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Play on YouTube").setContentText("Tap to play the video chosen on the glasses")
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_RECOMMENDATION)
        if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) builder.setFullScreenIntent(open, true)
        nm.notify(NOTIFICATION_ID, builder.build()); true
    }.getOrDefault(false)

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: android.content.ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    private fun watchUri(videoId: String) = Uri.parse(PhoneMediaStarter.watchUrl(videoId))

    companion object {
        const val CHANNEL = "media_start"
        const val NOTIFICATION_ID = 7310
    }
}
