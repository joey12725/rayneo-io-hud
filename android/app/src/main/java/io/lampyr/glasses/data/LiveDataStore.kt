package io.lampyr.glasses.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import io.lampyr.glasses.audio.*
import io.lampyr.glasses.settings.AppSettings
import io.lampyr.glasses.render.avatar.AvatarStates
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Optional transport bridge: deliberately independent of the RayNeo implementation. */
interface GlassesBatterySource { val batteryFraction: Double?; val connected: Boolean }

/** Beeper chat list page size, the most rows "Load more" keeps refreshed, and the Beeper screen kinds. */
private const val CHAT_PAGE = 100
private const val CHAT_MAX = 500
private val BEEPER_SCREENS = setOf("beeper", "thread", "beeper-actions")
internal const val BEEPER_VISIBLE_POLL_S = 20.0
internal const val BEEPER_HIDDEN_POLL_S = 60.0
/** A finished or failed assistant call worker task stays on the call screen this long. */
internal const val WORKER_LINGER_S = 4.0

class LiveDataStore(
    private val context: Context,
    private val scope: CoroutineScope,
    val gateway: GatewayClient = AppSettings(context).let { GatewayClient(it.url, it.token) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val nowS: () -> Double = { android.os.SystemClock.elapsedRealtime() / 1000.0 },
    startBackground: Boolean = true,
    private val callSessionFactory: (((String) -> Unit) -> JoyCallSession)? = null,
    private val memoEncoder: (File, File) -> Boolean = MemoOpusEncoder::encode,
    phoneApps: ((MediaHub) -> PhoneApps)? = null,
) : DataStore {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + job)
    private val cache = SnapshotCache(File(context.filesDir, "gateway-cache"))
    private val listeners = linkedSetOf<() -> Unit>()
    private var screen = "home"
    private var awake = true
    private var lastThread: ThreadKey? = null
    private var processedSequence = read("processed-sequence", 0L)
    private val visitedDone = mutableSetOf<String>()
    var onOpen: (OpenTarget) -> Unit = {}
    var glassesBattery: GlassesBatterySource? = null
    val mediaHub = MediaHub(context, work) { changed() }
    private val mediaStarter = PhoneMediaStarter(phoneApps?.invoke(mediaHub) ?: AndroidPhoneApps(context) { mediaHub })
    override val calls = io.lampyr.glasses.calls.AndroidCalls(context, work)
    private fun changed() { listeners.toList().forEach { it() } }
    val peopleEngine = io.lampyr.glasses.ambient.PeopleEngine(File(context.filesDir, "people/voices-heard.json"), gateway, nowMs, { changed() },
        { android.util.Log.w("People", it) })
    /**
     * Decoded views of cache entries, reused until the entry is replaced (checked by identity). Screens read these
     * getters on every render on the main thread; re-decoding the JSON each time cost Home 100-500 ms per frame.
     */
    private val decoded = java.util.concurrent.ConcurrentHashMap<String, Pair<JsonElement, Any?>>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> memo(name: String, source: JsonElement, build: () -> T): T {
        decoded[name]?.let { (from, value) -> if (from === source) return value as T }
        return build().also { decoded[name] = source to it }
    }
    private inline fun <reified T> read(key: String, fallback: T): T = runCatching {
        cache[key]?.let { element -> memo("read $key ${kotlin.reflect.typeOf<T>()}", element) { GatewayMappings.decode<T>(element) } } ?: fallback
    }.getOrDefault(fallback)
    private var spotifyAt: Double? = null
    private suspend fun remember(key: String, value: JsonElement) { cache.put(key, value); if (key == "/spotify/now") spotifyAt = nowS(); changed() }
    private suspend fun action(block: suspend () -> ActionResult): ActionResult = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { ActionResult.failure((e as? GatewayFailure)?.display ?: "Data unavailable · cached data shown") }
    private suspend fun load(path: String, key: String = path): ActionResult = action { remember(key, gateway.get(path)); ActionResult() }
    private suspend fun post(path: String, body: JsonElement = obj(), then: suspend () -> Unit = {}): ActionResult = action {
        val row = gateway.write(path, body); then(); ActionResult(id = row.s("id").ifBlank { null })
    }
    override fun addChangeListener(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    override val home get() = (runCatching { cache["/home"]?.let { memo("home ${java.time.ZoneId.systemDefault().id}", it) { GatewayMappings.home(it) } } }.getOrNull() ?: HomeSnapshot(weather = Weather(null, "Weather unavailable", "cloud"))).let { h ->
        h.copy(beeper = cache["/beeper/summary"]?.let { memo("home beeper", it) { HomeBeeper(it.n("unread").toInt(), it.a("names").map { n -> GatewayMappings.safeName(n.jsonPrimitive.content, "Chat") }) } } ?: h.beeper,
            t3 = if (cache["/t3/threads"] != null) t3.threads.let { threads -> HomeT3(threads.count { it.status in T3_NEEDS_YOU }, threads.count { it.status == "done" }) } else h.t3)
    }
    override suspend fun refreshHome() = load("/home").also { if (it.ok && seedConfig) work.launch { seedCompanionConfig() } }
    /** First contact with a gateway seeds the companion config (names, integrations, notification policy) once. */
    private var seedConfig = startBackground && io.lampyr.glasses.settings.CompanionConfig.current.seededAt == 0L
    private suspend fun seedCompanionConfig() {
        seedConfig = false
        try {
            val body = gateway.get("/config") as? JsonObject ?: return
            io.lampyr.glasses.settings.CompanionConfigStore(context).seed(body, nowMs()); changed()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* an older gateway without /config: keep the local config */ }
    }
    fun setDisplayAwake(value: Boolean) { awake = value }
    override fun onScreenChanged(screen: String) { this.screen = screen }

    private var joyState = read("joy", JoyState(since = nowS())).copy(status = "idle", anim = AvatarStates.IDLE, prompt = null, since = nowS())
    private var joyCall: Job? = null
    private var callDictation: GatewayDictation? = null
    private var liveCall: JoyCallSession? = null
    private var callAudio: AndroidJoyCallAudio? = null
    private var callState = JoyCallState()
    private var callExclusion: String? = null
    private var callGeneration = 0L
    private var stopCallListener: (() -> Unit)? = null
    private fun endJoyCall(reason: String) {
        callGeneration++
        liveCall?.close(reason); liveCall = null
        joyCall?.cancel(); joyCall = null
        callDictation?.cancel(); callDictation = null
        callAudio?.release(); callAudio = null
        callExclusion?.let { io.lampyr.glasses.ambient.CaptureExclusions.end(it) }; callExclusion = null
        callState = callState.copy(active = false, endedReason = reason, muted = false, worker = null)
        joyState = joyState.copy(status = "idle", anim = AvatarStates.IDLE, since = nowS()); changed()
    }
    /** Bridge `agent` events: the worker indicator. A finished task stays visible for [WORKER_LINGER_S] seconds. */
    internal fun callWorker(value: JoyWorker?) {
        if (!callState.active) return
        val since = nowS()
        callState = callState.copy(worker = value, workerSince = since)
        joyState = joyState.copy(anim = io.lampyr.glasses.render.CallAnimations.voice(joyState.status, callState.muted, value, 0.0)); changed()
        if (value != null && value.state in setOf("done", "error")) work.launch {
            delay((WORKER_LINGER_S * 1000).toLong())
            if (callState.workerSince == since) { callState = callState.copy(worker = null); changed() }
        }
    }
    private var busyJoy = false
    private var joyQuiet = false
    private val deliveredReplies = read("joy-delivered", emptyList<String>()).toMutableList()
    /** An `emote` row: `state` is an avatar state name; a numeric `anim` is a legacy id (1..50). */
    private fun emoteState(row: kotlinx.serialization.json.JsonElement): String {
        val named = row.s("state").ifBlank { row.s("anim") }
        val legacy = named.toDoubleOrNull()
        return if (legacy != null) AvatarStates.fromLegacyId(legacy.toInt()) ?: AvatarStates.IDLE else named.ifBlank { AvatarStates.IDLE }
    }

    private suspend fun joyStatus(status: String) {
        if (callState.active && callState.mode == "live") return // Only bridge states animate a live call.
        val mapped = GatewayMappings.joyStatus(status)
        joyState = joyState.copy(status = mapped, anim = AvatarStates.forStatus(mapped), since = nowS()); changed()
        if (mapped == "error") {
            val since = joyState.since
            work.launch { delay(1700); if (joyState.status == "error" && joyState.since == since) joyStatus("idle") }
        }
    }
    private suspend fun reply(text: String, session: String, at: String = "", origin: String = "stream", notificationId: String = "joy-${UUID.randomUUID()}") {
        val away = GatewayMappings.notifyJoy(screen)
        val key = java.security.MessageDigest.getInstance("SHA-256").digest("$session\u0000$text".toByteArray()).joinToString("") { "%02x".format(it) }
        val opposite = "${if (origin == "stream") "event" else "stream"}:$key"
        val paired = deliveredReplies.remove(opposite)
        if (!paired) deliveredReplies += "$origin:$key"
        while (deliveredReplies.size > 80) deliveredReplies.removeAt(0)
        remember("joy-delivered", gatewayJson.encodeToJsonElement(deliveredReplies))
        if (paired) return
        joyState = joyState.copy(sessionId = session.ifBlank { joyState.sessionId }, text = text, prompt = null)
        joyStatus("speaking"); remember("joy", gatewayJson.encodeToJsonElement(joyState))
        // The user asked and left the assistant screen: the reply pops up (lights a dark lens) unless they chose quiet wait.
        if (away) deliver(Notification(notificationId, "joy", "joy", "${io.lampyr.glasses.settings.Names.assistant} replied", text, .85, epoch(at), OpenTarget("joy"), if (joyQuiet) "stream" else "popup", "assistant.reply"))
        work.launch { delay(1150); if (joyState.text == text && joyState.status == "speaking") joyStatus("idle") }
    }
    override val joy = object : JoyRepository {
        override val snapshot get() = joyState
        override val call get() = callState
        override suspend fun refresh() = ActionResult()
        override suspend fun setListening(listening: Boolean): ActionResult { joyStatus(if (listening) "listening" else "idle"); return ActionResult() }
        override suspend fun dictationPhase(phase: String): ActionResult { joyStatus(phase); return ActionResult() }
        override suspend fun newConversation() = action {
            if (callState.active) return@action ActionResult.failure("${io.lampyr.glasses.settings.Names.assistant} call active")
            if (busyJoy) return@action ActionResult.failure("Wait for ${io.lampyr.glasses.settings.Names.assistant} to finish")
            val response = gateway.write("/assistant/sessions")
            joyState = JoyState(sessionId = response.s("sessionId"), since = nowS())
            remember("joy", gatewayJson.encodeToJsonElement(joyState)); ActionResult()
        }
        override suspend fun send(message: String, quiet: Boolean): ActionResult {
            if (message.isBlank()) { joyStatus("idle"); return ActionResult(message = "No speech detected") }
            if (callState.active && callState.mode != "turns") return ActionResult.failure("${io.lampyr.glasses.settings.Names.assistant} call active")
            if (busyJoy) return ActionResult.failure("${io.lampyr.glasses.settings.Names.assistant} is still working")
            busyJoy = true
            joyQuiet = quiet
            joyState = joyState.copy(prompt = message)
            joyStatus("thinking")
            // Return immediately, just as Fake does, so the UI remains interactive throughout a turn.
            work.launch {
                try {
                    val body = obj("message" to str(message), "sessionId" to str(joyState.sessionId), "quiet" to JsonPrimitive(quiet), "notifyIfLeft" to JsonPrimitive(true))
                    var terminal = false
                    gateway.stream("/assistant/chat", body.toString().toByteArray(), "application/json") { row ->
                        // Sequential main-dispatch processing preserves session/status/result ordering.
                        withContext(work.coroutineContext.minusKey(Job)) {
                            when (row.s("type")) {
                                "session" -> { joyState = joyState.copy(sessionId = row.s("sessionId")); changed() }
                                "status" -> joyStatus(row.s("status"))
                                "emote" -> { joyState = joyState.copy(anim = emoteState(row), since = nowS()); changed() }
                                "result" -> { terminal = true; reply(row.s("text"), row.s("sessionId", joyState.sessionId.orEmpty())) }
                                "error" -> { terminal = true; joyStatus("error"); addNotification(Notification("joy-error-${nowMs()}", "joy", "warn", "${io.lampyr.glasses.settings.Names.assistant} error", row.s("message"), .85, nowMs(), OpenTarget("joy"))) }
                            }
                        }
                    }
                    if (!terminal) throw GatewayFailure("${io.lampyr.glasses.settings.Names.assistant} reply interrupted")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { joyStatus("error"); addNotification(Notification("joy-error-${nowMs()}", "joy", "warn", "${io.lampyr.glasses.settings.Names.assistant} unavailable", (e as? GatewayFailure)?.display ?: "Check connection", .85, nowMs(), OpenTarget("joy"))) }
                finally { busyJoy = false }
            }
            return ActionResult()
        }
        override suspend fun startVoiceCall(): ActionResult {
            if (callState.active) return ActionResult()
            if (calls.snapshot.calls.isNotEmpty()) return ActionResult.failure("Phone call active")
            if (recording != null || busyJoy || joyState.status == "listening") return ActionResult.failure("Finish the current recording or ${io.lampyr.glasses.settings.Names.assistant} turn first")
            if (callSessionFactory == null && MicRegistry.glasses?.isAvailable() != true && io.lampyr.glasses.service.GlassesService.instance?.microphoneReady != true)
                return ActionResult.failure("Mic unavailable - open the phone app")
            if (callSessionFactory == null && MicRegistry.glasses?.isAvailable() != true && io.lampyr.glasses.service.GlassesService.instance?.microphoneReady != true)
                return ActionResult.failure("Mic unavailable - open the phone app")
            callState = JoyCallState(active = true, mode = "connecting")
            joyStatus("thinking")
            val generation = ++callGeneration
            callExclusion = io.lampyr.glasses.ambient.CaptureExclusions.begin("joy-call")
            joyCall = work.launch {
                try {
                    if (callSessionFactory == null) {
                        val audio = AndroidJoyCallAudio(context, mediaHub::pauseForJoyCall, { calls.snapshot.calls.isEmpty() }, releaseOnClose = false)
                        callAudio = audio
                        if (!audio.hold { if (callState.active) endJoyCall("audio interrupted") }) {
                            endJoyCall("microphone or audio unavailable"); return@launch
                        }
                    }
                    val available = try { withTimeoutOrNull(5000) { gateway.get("/assistant/call/status").b("available") } ?: false }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    if (generation != callGeneration) return@launch
                    val end = if (available) {
                        val update: (String) -> Unit = { value ->
                            val anim = io.lampyr.glasses.render.CallAnimations.voice(value, callState.muted, callState.worker, nowS() - callState.workerSince)
                            joyState = joyState.copy(status = value, anim = anim, since = nowS()); changed()
                        }
                        val session = callSessionFactory?.invoke(update) ?: JoyCallSession(gateway.callSocketFactory(),
                            checkNotNull(callAudio),
                            AmbientJoyCallExclusion(), { (nowS() * 1000).toLong() }, update, Dispatchers.Default,
                            worker = { w -> callWorker(w) })
                        liveCall = session
                        session.mute(callState.muted)
                        callState = callState.copy(mode = "live"); changed()
                        session.run().also { liveCall = null }
                    } else JoyCallEnd("unavailable", fallback = true)
                    if (generation != callGeneration) return@launch
                    if (end.fallback) {
                        callState = callState.copy(mode = "turns", muted = false); changed()
                        if (withTimeoutOrNull(15 * 60_000L) { voiceTurns(); true } == null) endJoyCall("cap")
                    } else endJoyCall(if (end.reason == "microphone unavailable") "Mic unavailable - open the phone app" else end.reason)
                } catch (e: TimeoutCancellationException) { if (generation == callGeneration) endJoyCall("timeout") }
                catch (e: CancellationException) { if (generation == callGeneration) endJoyCall("cancelled"); throw e }
                catch (e: Exception) { if (generation == callGeneration) endJoyCall((e as? GatewayFailure)?.display ?: "error") }
            }
            return ActionResult()
        }
        private suspend fun voiceTurns() {
            while (currentCoroutineContext().isActive) {
                joyStatus("listening")
                val transcript = CompletableDeferred<String>()
                val dictation = GatewayDictation(context, gateway, work); callDictation = dictation
                dictation.start("joy") { event ->
                    if (event is DictationEvent.Done) transcript.complete(event.text)
                    if (event is DictationEvent.Error) transcript.completeExceptionally(GatewayFailure(event.message))
                }
                try {
                    delay(6000); joyStatus("thinking"); dictation.stop()
                    val text = withTimeout(180_000) { transcript.await() }
                    if (text.isBlank()) continue // Silence is a successful turn; keep listening.
                    send(text)
                    while (busyJoy) delay(200)
                    delay(3000)
                } finally { dictation.cancel(); if (joyState.status == "listening") joyStatus("idle") }
            }
        }
        override suspend fun muteVoiceCall(): ActionResult {
            if (!callState.active || callState.mode == "turns") return ActionResult.failure("Mute available in live calls")
            callState = callState.copy(muted = !callState.muted); liveCall?.mute(callState.muted)
            joyState = joyState.copy(anim = io.lampyr.glasses.render.CallAnimations.voice(joyState.status, callState.muted, callState.worker, nowS() - callState.workerSince), since = nowS()); changed()
            return ActionResult()
        }
        override suspend fun endVoiceCall(): ActionResult { if (callState.active) endJoyCall("user"); return ActionResult() }
    }

    private val messageStates = ConcurrentHashMap<String, MessagePageState>()
    private val messageLoads = ConcurrentHashMap<String, Mutex>()
    private var chatMemo: Pair<JsonElement, List<Chat>>? = null
    private val openThreads: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val chatPages = Mutex()
    @Volatile private var chatLimit = CHAT_PAGE
    /** BEEP-PRI list order; PRIORITY is the gateway default, so only RECENT adds a query parameter. */
    @Volatile private var beeperOrder = BeeperOrder.PRIORITY
    private fun orderQuery() = if (beeperOrder == BeeperOrder.RECENT) "&order=recent" else ""
    /** Chats a spoken find returned that the loaded page does not hold, so their thread still has a name. */
    private val foundChats = ConcurrentHashMap<String, Chat>()
    @Volatile private var nicknameMemo: Pair<Long, Map<String, List<String>>>? = null
    /** A Beeper screen is on top: poll every 20 s (60 s otherwise), whether or not the display is asleep. */
    private fun beeperVisible() = screen in BEEPER_SCREENS || (openThreads.isNotEmpty() && screen in setOf("reader", "react"))
    /** Beeper list (and open threads) every 20 s while a Beeper screen is on top, 60 s otherwise; never gated on
     * display sleep (the display sleeps after 2 s). Pushed `beeper` events refresh sooner. */
    internal suspend fun beeperPoll(tickMs: Long = 5_000) {
        var last = Double.NEGATIVE_INFINITY
        while (currentCoroutineContext().isActive) {
            if (nowS() - last >= (if (beeperVisible()) BEEPER_VISIBLE_POLL_S else BEEPER_HIDDEN_POLL_S) - 0.001) {
                last = nowS()
                refreshBeeper()
            }
            delay(tickMs)
        }
    }
    private suspend fun refreshBeeper(chatIds: Collection<String> = openThreads.toList()) {
        beeper.refreshChats()
        chatIds.filter { it in openThreads }.forEach { beeper.refreshMessages(it) }
    }
    private val pushedChats: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pushedAll = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pushQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pushLock = Mutex()
    /** Pushed `beeper` hints are conflated: at most one refresh runs and one more waits, however many events arrive
     * (a reconnect can deliver a backlog at once). An empty [chatIds] (reconcile) refreshes every open thread. */
    private fun pushBeeper(chatIds: List<String>) {
        if (chatIds.isEmpty()) pushedAll.set(true) else pushedChats += chatIds
        if (!pushQueued.compareAndSet(false, true)) return
        work.launch {
            pushLock.withLock {
                pushQueued.set(false)
                val all = pushedAll.getAndSet(false)
                val ids = pushedChats.toList().also { pushedChats.removeAll(it.toSet()) }
                refreshBeeper(if (all) openThreads.toList() else ids)
            }
        }
    }
    override val beeper = object : BeeperRepository {
        // The gateway's `unread` counts conversations (muted and spam excluded); fallbacks use the same rule.
        override val unreadTotal get() = cache["/beeper/summary"]?.n("unread")?.toInt() ?: home.beeper?.unread ?: chats.count { it.countsTowardUnread }
        override val unreadChats get() = cache["/beeper/summary"]?.n("unreadChats")?.toInt() ?: chats.count { it.countsTowardUnread }
        // Decoded once per cached snapshot (the list draws every frame and may hold hundreds of rows).
        override val chats: List<Chat> get() {
            val raw = cache["/beeper/chats"] ?: return emptyList()
            chatMemo?.let { (source, decoded) -> if (source === raw) return decoded }
            return (runCatching { GatewayMappings.chats(raw) }.getOrNull().orEmpty()).also { chatMemo = raw to it }
        }
        override val hasMoreChats get() = (cache["/beeper/summary"]?.n("total", -1.0)?.toInt() ?: -1) > chats.size
        override fun threadVisible(chatId: String, visible: Boolean) { if (visible) openThreads.add(chatId) else openThreads.remove(chatId) }
        override suspend fun loadMoreChats(): ActionResult = chatPages.withLock {
            // Offset by the gateway rows held, not the decoded ones (a skipped malformed row must not shift the page).
            val have = (cache["/beeper/chats"] as? JsonArray)?.size ?: 0
            action {
                val page = gateway.get("/beeper/chats?limit=$CHAT_PAGE&offset=$have" + orderQuery())
                val merged = ((cache["/beeper/chats"] as? JsonArray).orEmpty() + (page as? JsonArray).orEmpty()).distinctBy { it.s("id") }
                chatLimit = merged.size.coerceIn(CHAT_PAGE, CHAT_MAX)
                remember("/beeper/chats", JsonArray(merged)); ActionResult()
            }
        }
        override suspend fun setSpam(chatId: String, spam: Boolean) = post("/beeper/chats/${segment(chatId)}/spam", obj("spam" to JsonPrimitive(spam)), { refreshChats() })
            .let { if (it.ok) it.copy(message = if (spam) "Marked spam" else "Marked not spam") else it }
        override fun chat(chatId: String) = chats.find { it.id == chatId } ?: foundChats[chatId]
        override fun useOrder(order: BeeperOrder) {
            if (beeperOrder == order) return
            beeperOrder = order
            work.launch { refreshChats() }
        }
        override suspend fun setPin(chatId: String, pinned: Boolean) = post("/beeper/chats/${segment(chatId)}/pin", obj("pinned" to JsonPrimitive(pinned), "name" to str(chat(chatId)?.name)), { refreshChats() })
            .let { if (it.ok) it.copy(message = if (pinned) "Pinned on glasses" else "Unpinned") else it }
        override suspend fun nicknames(): Map<String, List<String>> {
            nicknameMemo?.let { (at, value) -> if (nowMs() - at < 10 * 60_000) return value }
            return withContext(Dispatchers.IO) { ContactNicknames.load(context) }.also { nicknameMemo = nowMs() to it }
        }
        override suspend fun find(query: String): BeeperFind {
            val local = BeeperPriority.find(query, chats, nicknames())
            // The gateway searches every listed chat (the phone holds one page); a failure keeps the local answer.
            // Bounded: an unreachable gateway must not hold a spoken lookup for the client's 180 s call timeout.
            val remote = runCatching { withContext(Dispatchers.IO) { withTimeoutOrNull(4000) { gateway.get("/beeper/find?q=${segment(query)}&limit=5") } } }.getOrNull()
            val rows = remote?.a("matches").orEmpty()
            val decoded = runCatching { GatewayMappings.chats(JsonArray(rows)) }.getOrNull().orEmpty()
            val scores = rows.associate { it.s("id") to ((it as? JsonObject)?.get("match")?.n("score") ?: 0.0) }
            decoded.filter { found -> chats.none { it.id == found.id } }.forEach { foundChats[it.id] = it }
            val merged = (local + decoded.map { BeeperPriority.Match(chats.find { c -> c.id == it.id } ?: it, scores[it.id] ?: 0.0, "") })
                .groupBy { it.chat.id }.map { (_, same) -> same.maxBy { it.score } }
                .sortedByDescending { it.score }.take(5)
            return BeeperFind(merged, BeeperPriority.confident(merged))
        }
        override fun messages(chatId: String) = read<List<Message>>("messages:$chatId", emptyList()).map(GatewayMappings::message)
        override fun pageState(chatId: String) = messageStates[chatId] ?: MessagePageState(loaded = cache["messages:$chatId"] != null)
        override suspend fun refreshChats(): ActionResult {
            // Up to 100 rows (or as many as "Load more" has shown), cached under the plain key.
            val results = listOf("/beeper/chats?limit=$chatLimit" + orderQuery() to "/beeper/chats","/beeper/summary" to "/beeper/summary").map { (path, key) -> work.async { load(path, key) } }.awaitAll()
            return results.firstOrNull { !it.ok } ?: ActionResult()
        }
        private suspend fun fetchMessages(chatId: String, older: Boolean): ActionResult = messageLoads.computeIfAbsent(chatId) { Mutex() }.withLock {
            val state = pageState(chatId)
            val initial = !messageStates.containsKey(chatId)
            if (older && state.nextBefore == null) return@withLock ActionResult()
            messageStates[chatId] = state.copy(loadingOlder = older); changed()
            var completed = false
            try { action {
                val row = gateway.get("/beeper/chats/${segment(chatId)}/messages?page=true" + if (older) "&before=${segment(state.nextBefore!!)}" else "")
                val page = if (row is JsonArray) row else row.a("messages")
                val fresh = GatewayMappings.decode<List<Message>>(page).map(GatewayMappings::message)
                val merged = (messages(chatId) + fresh).associateBy { it.id }.values.toList().let { list ->
                    if (list.all { it.at.isNotBlank() }) list.sortedBy { runCatching { Instant.parse(it.at).toEpochMilli() }.getOrDefault(0) } else if (older) (fresh + messages(chatId)).distinctBy { it.id } else (messages(chatId).filterNot { old -> fresh.any { it.id == old.id } } + fresh)
                }
                remember("messages:$chatId", gatewayJson.encodeToJsonElement(merged))
                messageStates[chatId] = MessagePageState(loaded = true, nextBefore = if (older || initial) row.s("nextBefore").ifBlank { null } else state.nextBefore)
                completed = true
                changed(); ActionResult()
            } } finally {
                if (initial && !completed) messageStates.remove(chatId) else messageStates[chatId] = pageState(chatId).copy(loadingOlder = false)
                changed()
            }
        }
        override suspend fun refreshMessages(chatId: String) = fetchMessages(chatId, false)
        override suspend fun loadOlder(chatId: String) = fetchMessages(chatId, true)
        override suspend fun markRead(chatId: String) = post("/beeper/chats/${segment(chatId)}/read") { refreshChats() }
        override suspend fun send(chatId: String, text: String, replyTo: String?) = post("/beeper/chats/${segment(chatId)}/messages", obj("text" to str(text), "replyTo" to str(replyTo))) { refreshMessages(chatId); refreshChats() }
        override suspend fun send(chatId: String, text: String, replyTo: String?, mentions: List<Mention>) = post("/beeper/chats/${segment(chatId)}/messages",
            obj("text" to str(text), "replyTo" to str(replyTo), "mentions" to mentions.takeIf { it.isNotEmpty() }?.let(Mentions::json))) { refreshMessages(chatId); refreshChats() }
        override suspend fun react(chatId: String, messageId: String, key: String, remove: Boolean) = action {
            val message = messages(chatId).find { it.id == messageId }
            val removing = remove || message?.reactions?.any { it.key == key && it.mine } == true
            val path = "/beeper/chats/${segment(chatId)}/messages/${segment(messageId)}/reactions"
            gateway.write(if (removing) "$path?key=${segment(key)}" else path, obj("key" to str(key)), if (removing) "DELETE" else "POST")
            refreshMessages(chatId)
            ActionResult(message = GatewayMappings.approvalToast(message?.approval == true, key, removing))
        }
    }

    override val t3 = object : T3Repository {
        override val machines get() = readMachines()
        private fun readMachines() = cache["/t3/threads"]?.let { source -> memo("t3 machines", source) { source.a("machines").map { GatewayMappings.decode<Environment>(it) } } }.orEmpty()
        // Rows are decoded once per snapshot; only the clock-dependent step and the machine lookup run per read.
        override val threads get() = runCatching {
            val source = cache["/t3/threads"] ?: return@runCatching emptyList()
            val rows = memo("t3 threads", source) { source.a("threads").map { GatewayMappings.decode<Thread>(it) } }
            val machines = readMachines(); val epochMs = nowMs(); val monotonic = nowS()
            rows.map { it.copy(step = GatewayMappings.step(it.step, epochMs, monotonic), machineOnline = machines.find { m -> m.machine == it.machine }?.online ?: true) }
        }.getOrNull().orEmpty()
        override val settled get() = read<List<Thread>>("/t3/settled", emptyList())
        override val usage get() = read<List<Usage>>("/t3/usage", emptyList()).map { usage -> usage.copy(windows = usage.windows.map { w ->
            val remain = runCatching { (Instant.parse(w.resetsAt).toEpochMilli() - nowMs()) / 60000.0 }.getOrDefault(0.0)
            w.copy(elapsedFraction = if (w.windowDurationMins > 0) (1 - remain / w.windowDurationMins).coerceIn(0.0, 1.0) else 0.0)
        }) }
        // One source of truth: the machine rows polled with /t3/threads, so "x/y online", the offline count and
        // the Environments list always agree. /t3/environments only fills what an older gateway left out (os).
        override val environments get() = read<List<Environment>>("/t3/environments", emptyList()).let { listed ->
            machines.ifEmpty { listed }.map { m -> if (m.os.isNotBlank()) m else m.copy(os = listed.find { it.machine == m.machine }?.os.orEmpty()) }
        }.map { it.copy(lastSeen = it.lastSeen.ifBlank { "Never seen" }, lastSeenLabel = it.lastSeenLabel.ifBlank { if (it.lastSeen.isBlank()) "Never seen" else "Last seen ${it.lastSeen}" }) }
        override val projects get() = read<List<Project>>("/t3/projects", emptyList())
        override fun detail(machine: String, id: String) = runCatching { cache["thread:$machine:$id"]?.let { GatewayMappings.detail(it, nowMs(), nowS()) } }.getOrNull()
        // T3 notifications come from the gateway's own notifier (server lib/t3-notify.mjs: polls every machine, detects
        // needs-you and finished, persists dedupe, publishes "notification" events). The thread poll here only refreshes
        // the list, so a notification never appears twice and none depends on the display being awake.
        override suspend fun refreshThreads(): ActionResult = load("/t3/threads")
        override val notifyAllNeeds get() = cache["/t3/notify/settings"]?.let { (it as? JsonObject)?.get("needsAll")?.jsonPrimitive?.booleanOrNull } ?: true
        override suspend fun refreshNotifySettings() = load("/t3/notify/settings")
        override suspend fun setNotifyAllNeeds(on: Boolean) = action {
            val saved = gateway.write("/t3/notify/settings", obj("needsAll" to JsonPrimitive(on)))
            remember("/t3/notify/settings", saved.takeIf { it is JsonObject && it.jsonObject["needsAll"] != null } ?: obj("needsAll" to JsonPrimitive(on)))
            ActionResult(message = if (on) "Notifying: any thread needing you" else "Notifying: watched threads only")
        }
        override suspend fun refreshSettled() = load("/t3/settled")
        override suspend fun refreshUsage() = load("/t3/usage")
        override suspend fun refreshEnvironments(): ActionResult { val result = refreshThreads(); load("/t3/environments"); return result }
        override suspend fun refreshProjects() = load("/t3/projects")
        private fun path(machine: String, id: String) = "/t3/threads/${segment(machine)}/${segment(id)}"
        override suspend fun refreshThread(machine: String, id: String): ActionResult {
            lastThread = ThreadKey(machine, id)
            val result = load(path(machine, id), "thread:$machine:$id")
            val fresh = detail(machine, id)?.thread
            if (result.ok && screen == "t3thread" && fresh?.status == "done" && visitedDone.add("$machine:$id:${fresh.updatedAt}")) post(path(machine, id) + "/visit") { refreshThreads() }
            return result
        }
        override suspend fun visit(machine: String, id: String) = if (detail(machine, id)?.thread?.status == "done" || threads.any { it.machine == machine && it.id == id && it.status == "done" }) post(path(machine, id) + "/visit") { refreshThreads() } else ActionResult()
        override suspend fun send(machine: String, id: String, text: String, mode: String): ActionResult {
            val chosen = GatewayMappings.sendMode(detail(machine, id)?.thread?.status, mode)
            return post(path(machine, id) + "/messages", obj("text" to str(text), "mode" to str(chosen))) { refreshThread(machine, id); refreshThreads(); refreshSettled() }
        }
        override suspend fun respond(machine: String, id: String, requestId: String, decision: String?, answers: Map<String, List<String>>): ActionResult {
            val request = detail(machine, id)?.request
            val response = JsonObject(answers.mapValues { (q, values) -> if (request?.questions?.find { it.id == q }?.multi == true) JsonArray(values.map(::JsonPrimitive)) else JsonPrimitive(values.firstOrNull().orEmpty()) })
            return post(path(machine, id) + "/respond", obj("requestId" to str(requestId), "decision" to str(decision), "answers" to response.takeIf { answers.isNotEmpty() })) { refreshThread(machine, id); refreshThreads() }
        }
        override suspend fun newThread(machine: String, projectId: String, text: String) = post("/t3/threads", obj("machine" to str(machine), "projectId" to str(projectId), "text" to str(text))) { refreshThreads() }
        override suspend fun resetUsage(machine: String, instanceId: String, creditId: String) = post("/t3/usage/reset", obj("machine" to str(machine), "instanceId" to str(instanceId), "creditId" to str(creditId))) { refreshUsage() }
        override val watched: Set<String> get() = watchedThreads()
        override suspend fun watch(machine: String, id: String, title: String, on: Boolean) = action {
            gateway.write("/t3/watch", obj("machine" to str(machine), "id" to str(id), "title" to str(title.take(200)), "on" to JsonPrimitive(on)))
            val key = "$machine:$id"
            val rows = (cache["/t3/watch"] as? JsonArray).orEmpty().filter { (it as? JsonObject)?.s("key") != key } +
                if (on) listOf(obj("key" to str(key), "machine" to str(machine), "id" to str(id), "title" to str(title.take(200)))) else emptyList()
            remember("/t3/watch", JsonArray(rows))
            ActionResult(message = if (on) "Watching: you'll be notified" else "Stopped watching")
        }
    }
    private fun watchedThreads(): Set<String> = (cache["/t3/watch"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.s("key")?.ifBlank { null } }.toSet()
    private fun watchedTitle(text: String): String? = (cache["/t3/watch"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .filter { row -> row.s("title").trim().length >= 4 && text.lowercase().contains(row.s("title").trim().lowercase()) }.singleOrNull()?.s("key")
    /** Text-free routing check of a phone notification with this store's T3 watch list (debug bridge /notify/active). */
    fun checkPhoneNotification(n: NoticeInput): Map<String, Any> = NotificationRules.check(scrubbedPhone(n), { it in watchedThreads() }, ::watchedTitle)
    /** What phoneNotification routes and shows: a safe title, ids scrubbed from the body and conversation. */
    private fun scrubbedPhone(input: NoticeInput): NoticeInput = input.copy(
        title = GatewayMappings.safeName(input.title, "Message", GatewayMappings.phoneNumberNetwork(input.network) && !input.isGroup),
        body = GatewayMappings.scrubIds(input.body), conversationTitle = GatewayMappings.scrubIds(input.conversationTitle))
    private suspend fun refreshWatched() = load("/t3/watch")

    override val media = object : MediaRepository {
        override val current get() = cache["/spotify/home"]?.jsonObject?.get("current")?.takeUnless { it == JsonNull }?.let { GatewayMappings.decode<NowPlaying>(it) }?.let { it.copy(volume = (it.volume * 15.0 / 100).toInt().coerceIn(0, 15)) }
        override val nowPlaying get(): NowPlaying? {
            val remote = (read<NowPlaying?>("/spotify/now", null) ?: home.nowPlaying)?.let {
                spotifyAt?.let { at -> GatewayMappings.interpolate(it, nowS() - at) } ?: it.copy(playing = false)
            }
            val phone = mediaHub.snapshot
            // An active session also owns its paused state; cached remote playback must not revive it.
            if (phone != null) return phone
            return remote?.let { now ->
                if (now.source == "spotify") read<NowPlaying?>("/spotify/now", null)?.let { now.copy(device = it.device, volume = (it.volume * 15.0 / 100).toInt().coerceIn(0, 15)) } ?: now else now
            }
        }
        override val books get() = read<List<Book>>("/abs/recent", emptyList())
        override val playlists get() = cache["/spotify/home"]?.let { row ->
            val values = listOfNotNull(row.jsonObject["discoverWeekly"]?.takeUnless { it == JsonNull }, row.jsonObject["liked"]) + row.a("recent")
            values.mapNotNull { runCatching { GatewayMappings.decode<Playlist>(it) }.getOrNull() }.distinctBy { it.uri }
        }.orEmpty()
        override val devices get() = read<List<Device>>("/spotify/devices", emptyList())
        /** (key, name) of the gateway's YouTube lists (GET /youtube/lists); the first one is the list the glasses show. */
        private fun youtubeListRows(): List<Pair<String, String>> = (cache["/youtube/lists"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { row -> row.s("key").ifBlank { null }?.let { key -> key to row.s("name").ifBlank { key } } } }
        override val youtubeLists get() = youtubeListRows().map { it.second }.ifEmpty { listOf(DEFAULT_YOUTUBE_LIST_NAME) }
        override fun videos(key: String) = read<List<Video>>("videos:$key", emptyList())
        override suspend fun refresh(): ActionResult {
            val results = listOf("/spotify/now", "/spotify/home", "/spotify/devices", "/abs/recent", "/youtube/lists").map { path -> work.async { load(path) } }.awaitAll()
            mediaHub.refresh(); return results.firstOrNull { !it.ok } ?: ActionResult()
        }
        override suspend fun refreshVideos(key: String): ActionResult {
            val rows = youtubeListRows()
            val listKey = rows.firstOrNull { it.second == key }?.first ?: rows.firstOrNull()?.first ?: DEFAULT_YOUTUBE_LIST_KEY
            return load("/youtube/list/${java.net.URLEncoder.encode(listKey, "UTF-8")}?limit=25", "videos:$key")
        }
        override suspend fun control(action: String, value: Double?) = action {
            val source = nowPlaying?.source
            val remoteSpotify = source == "spotify" && mediaHub.snapshot?.playing != true && (read<NowPlaying?>("/spotify/now", null)?.playing == true || mediaHub.snapshot == null)
            if (source == "spotify" && action == "volume") {
                gateway.write("/spotify/control", obj("action" to str("volume"), "value" to JsonPrimitive(((value ?: 0.0) / 15 * 100).toInt())))
            } else if (remoteSpotify || !mediaHub.control(action, value)) {
                if (source != "spotify") return@action ActionResult.failure("Open a media app on the phone")
                gateway.write("/spotify/control", obj("action" to str(if (action == "toggle") if (nowPlaying?.playing == true) "pause" else "play" else action), "value" to value?.let(::JsonPrimitive)))
            }
            load("/spotify/now"); mediaHub.refresh(); ActionResult()
        }
        override suspend fun play(source: String, id: String) = action {
            when (source) {
                "spotify" -> {
                    AbsPlayer.instance?.pause()
                    // 409: nothing is active and the gateway could not pick the phone. Start Spotify on the phone and wait for
                    // it to register as a Connect device; without the app (or on timeout) the glasses show the device picker.
                    try { gateway.write("/spotify/play", obj("uri" to str(id))) }
                    catch (e: GatewayFailure) {
                        if (e.code != 409) throw e
                        val started = mediaStarter.startSpotify(id,
                            devices = { gateway.get("/spotify/devices").also { remember("/spotify/devices", it) }; devices },
                            play = { deviceId -> gateway.write("/spotify/play", obj("uri" to str(id), "deviceId" to str(deviceId))) })
                        if (!started.ok) { load("/spotify/devices"); return@action started }
                    }
                    load("/spotify/now")
                }
                "abs" -> {
                    val row = gateway.write("/abs/play/${segment(id)}")
                    val result = AbsPlayer.play(context, id, row)
                    if (!result.ok) return@action result
                }
                "youtube" -> {
                    AbsPlayer.instance?.pause()
                    // Focus mode, a locked phone and a blocked start each come back as a message for the glasses.
                    val started = mediaStarter.startYouTube(id)
                    mediaHub.refresh(); return@action started
                }
                else -> return@action ActionResult.failure("Unknown media source")
            }
            mediaHub.refresh(); ActionResult()
        }
        override suspend fun transfer(deviceId: String) = post("/spotify/transfer", obj("deviceId" to str(deviceId))) { load("/spotify/now"); load("/spotify/devices") }
        override val libraryPlaylists get() = cache["/spotify/home"]?.a("playlists")?.mapNotNull { runCatching { GatewayMappings.decode<Playlist>(it) }.getOrNull() }?.distinctBy { it.uri }.orEmpty()
        override val spotifyErrors get() = cache["/spotify/home"]?.a("errors")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        override val startStatus get() = mediaStarter.status
        override suspend fun playOn(uri: String, deviceId: String) = action {
            AbsPlayer.instance?.pause()
            gateway.write("/spotify/play", obj("uri" to str(uri), "deviceId" to str(deviceId)))
            load("/spotify/now"); load("/spotify/devices"); mediaHub.refresh(); ActionResult()
        }
    }
    override val calendar = object : CalendarRepository {
        override val agenda get() = cache["/calendar/agenda"]?.let(GatewayMappings::agenda).orEmpty()
        override val dateLabel get() = LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM d"))
        override suspend fun refresh() = load("/calendar/agenda")
    }
    override val people = object : PeopleRepository {
        override val notice get() = peopleEngine.lastError ?: peopleEngine.modelNotice()
        override val snapshot get() = peopleEngine.merge(read("/people/recent", PeopleSnapshot()).let { s ->
            s.copy(recent = s.recent.map { p -> read("person:${p.id}", p).let { card -> p.copy(notes = card.notes, muted = card.muted) } })
        })
        override suspend fun refresh() = load("/people/recent")
        override suspend fun refreshPerson(personId: String) = load("/people/${segment(personId)}", "person:$personId")
        override suspend fun mute(personId: String, muted: Boolean) = peopleEngine.mute(personId, muted)
        override suspend fun remind(personId: String, text: String) = post("/reminders", obj("text" to str(text), "condition" to obj("type" to str("person"), "value" to str(personId)))) { reminders.refresh() }.let { if (it.ok) it.copy(message = "Reminder armed") else it }
    }
    override val reminders = object : RemindersRepository {
        override val armed get() = read<List<Reminder>>("/reminders", emptyList()).filter { runCatching { Instant.parse(it.expiresAt).toEpochMilli() > nowMs() }.getOrDefault(true) }
        override suspend fun refresh() = load("/reminders")
        override suspend fun cancel(id: String) = action { gateway.write("/reminders/${segment(id)}", method = "DELETE"); refresh() }
    }
    override val health = object : HealthRepository {
        override val metrics get() = cache["/health/overview"]?.let { GatewayMappings.metrics(it, nowMs()) }.orEmpty()
        override val updatedLabel get() = metrics.map { it.measuredAtRaw }.filter { it.isNotBlank() }.maxOrNull()?.let { "Updated ${GatewayMappings.measuredAt(it, nowMs())}" } ?: "No measurements"
        override suspend fun refresh() = load("/health/overview")
    }
    override val ha = object : HaRepository {
        override val panel get() = read("/ha/panel", HomeAssistantPanel())
        override suspend fun refresh() = load("/ha/panel")
        override suspend fun scene(id: String) = post("/ha/scene", obj("id" to str(id))) { refresh() }
        override suspend fun alarm(time: String?, style: String?) = post("/ha/alarm", obj("time" to str(time), "style" to str(style))) { refresh() }
    }
    override val docs = object : DocsRepository {
        override val documents get() = read<List<Document>>("/docs", emptyList()).map { doc -> read("doc:${doc.id}", doc).copy(createdAt = doc.createdAt) }
        override suspend fun refresh() = load("/docs")
        override suspend fun refreshDocument(id: String) = load("/docs/${segment(id)}", "doc:$id")
        override suspend fun delete(id: String) = action { gateway.write("/docs/${segment(id)}", method = "DELETE"); refresh() }
    }
    override val report = object : ReportRepository {
        override val snapshot get() = read("/report/latest", ReportSnapshot())
        override suspend fun refresh() = load("/report/latest")
    }
    private var notificationItems = read<List<Notification>>("notifications", emptyList())
    private val dismissed = read<List<String>>("dismissed", emptyList()).toMutableSet()
    // Dismissals are remembered per notification *revision* (id + content hash): an app that updates the same
    // notification key with a newer message shows up again instead of being hidden forever.
    private fun revision(n: Notification) = "${n.id}@${(n.title + "|" + n.body).hashCode()}"
    private fun isDismissed(n: Notification) = n.id in dismissed || revision(n) in dismissed
    private suspend fun addNotification(value: Notification) {
        if (isDismissed(value)) return
        notificationItems = (listOf(value) + notificationItems.filterNot { it.id == value.id }).take(200)
        remember("notifications", gatewayJson.encodeToJsonElement(notificationItems))
    }
    private val popupListeners = linkedSetOf<(Notification) -> Unit>()
    /** Routing contract delivery: stream joins the list; popup joins the list and tells the UI (which may show a card). */
    private suspend fun deliver(value: Notification) {
        if (value.route == "suppress" || isDismissed(value)) return
        // A popup that arrives late (an event replayed after the phone was offline, an old phone notification) only
        // joins the list: lighting the lens minutes later for something stale surprises the wearer.
        val n = if (value.route == "popup" && nowMs() - value.at > POPUP_MAX_AGE_MS) value.copy(route = "stream") else value
        addNotification(n)
        if (n.route == "popup") popupListeners.toList().forEach { it(n) }
    }
    /** Last routed content per phone notification key: Android re-posts unchanged notifications (updates, re-alerts). */
    private val routedPhone = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 300
    }
    /** The phone app withdrew its notification (read on the phone): drop the row, no dismissal is recorded. */
    suspend fun withdrawPhoneNotification(key: String) {
        routedPhone.remove(key)
        val id = "phone:$key"
        if (notificationItems.none { it.id == id }) return
        notificationItems = notificationItems.filterNot { it.id == id }
        remember("notifications", gatewayJson.encodeToJsonElement(notificationItems))
    }
    override val notifications = object : NotificationsRepository {
        override fun listenPopups(listener: (Notification) -> Unit): () -> Unit { popupListeners += listener; return { popupListeners -= listener } }
        override suspend fun resolveOpen(n: Notification): OpenTarget? {
            val open = n.open ?: return null
            // A Beeper row whose chat the gateway could not resolve on arrival: try again now (the chat list is fresher).
            if (open.screen != "beeper" || open.args["target"] != null || open.args["name"].isNullOrBlank()) return open
            val resolved = try {
                withTimeoutOrNull(4000) { gateway.write("/beeper/resolve", obj("title" to str(open.args["name"]), "conversationTitle" to str(open.args["conversation"]), "sender" to str(open.args["sender"]),
                    "text" to str(open.args["preview"]), "network" to str(open.args["network"]), "isGroup" to JsonPrimitive(open.args["group"] == "true"))) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val chatId = resolved?.takeIf { it.b("resolved") }?.s("chatId")?.ifBlank { null }
            return OpenTarget("beeper", chatId?.let { mapOf("target" to it) } ?: emptyMap())
        }
        override val items get() = sortNotifications(notificationItems.filterNot(::isDismissed), nowMs())
        override suspend fun refresh() = ActionResult()
        override suspend fun dismiss(id: String): ActionResult { dismissed += notificationItems.firstOrNull { it.id == id }?.let(::revision) ?: id; if (dismissed.size > 1000) dismissed.retainAll(dismissed.toList().takeLast(500).toSet()); remember("dismissed", gatewayJson.encodeToJsonElement(dismissed.toList())); changed(); return ActionResult() }
        override suspend fun clear(): ActionResult { dismissed += notificationItems.map(::revision); notificationItems = emptyList(); remember("dismissed", gatewayJson.encodeToJsonElement(dismissed.toList())); remember("notifications", JsonArray(emptyList())); return ActionResult() }
    }
    /**
     * One phone notification through the routing contract: the offline rules first (NotificationRules), then
     * POST /notify/route for semantic cases and for every Beeper message (only the gateway knows mute and spam).
     * If the gateway does not answer in time the offline fallback applies, which never pops up.
     */
    suspend fun phoneNotification(id: String, input: NoticeInput, postedAt: Long? = null) = action {
        val n = scrubbedPhone(input); val title = n.title
        // The same content re-posted under the same key: no second classifier call, no second popup.
        val fingerprint = listOf(n.title, n.body, n.conversationTitle, n.sender).joinToString("|").hashCode()
        if (routedPhone[id] == fingerprint) return@action ActionResult()
        routedPhone[id] = fingerprint
        val started = nowMs()
        val local = NotificationRules.decide(n, { it in watchedThreads() }, ::watchedTitle)
        val decision = if (local?.route == "suppress" || (local != null && n.kind != "beeper")) local
            else routeRemote(n) ?: local ?: NotificationRules.fallback(n)
        io.lampyr.glasses.debug.Trace.log("Notify", "pkg=${n.source} ch=${n.channelId.ifBlank { "-" }} route=${decision.route} by=${decision.by} reason=${if (decision.by == "model") "model" else decision.reason} ms=${nowMs() - started}")
        if (decision.route == "suppress") return@action ActionResult()
        val beeper = n.kind == "beeper"
        // Beeper: a group row reads as the conversation, with the sender in the body.
        val shownTitle = if (beeper && n.isGroup && n.conversationTitle.isNotBlank()) n.conversationTitle else title
        val shownBody = if (beeper && n.isGroup && n.sender.isNotBlank() && !n.body.startsWith(n.sender + ":")) "${n.sender}: ${n.body}" else n.body
        val app = decision.app
        val open = when {
            app == null -> null
            decision.target != null -> OpenTarget(app, mapOf("target" to decision.target))
            beeper -> OpenTarget("beeper", mapOf("name" to title, "conversation" to n.conversationTitle, "sender" to n.sender, "preview" to n.body.take(80),
                "network" to n.network, "group" to n.isGroup.toString()))
            else -> OpenTarget(app)
        }
        deliver(Notification("phone:$id", n.source, if (beeper) "beeper" else if (n.kind == "t3") "t3" else "bell", shownTitle, shownBody, decision.importance,
            (postedAt ?: nowMs()).coerceAtMost(nowMs()), open, decision.route, decision.reason))
        ActionResult()
    }

    private suspend fun routeRemote(n: NoticeInput): NoticeDecision? = try {
        withTimeoutOrNull(NOTIFY_ROUTE_TIMEOUT_MS) {
            val row = gateway.write("/notify/route", obj("kind" to str(n.kind), "source" to str(n.source), "app" to str(n.app), "title" to str(n.title), "body" to str(n.body),
                "conversationTitle" to str(n.conversationTitle), "sender" to str(n.sender), "network" to str(n.network), "isGroup" to JsonPrimitive(n.isGroup),
                "chatId" to str(n.chatId.ifBlank { null }), "channelId" to str(n.channelId.ifBlank { null }), "category" to str(n.category.ifBlank { null })))
            val route = row.s("route").takeIf { it in setOf("suppress", "stream", "popup") } ?: return@withTimeoutOrNull null
            val importance = row.n("importance", -1.0).takeIf { it.isFinite() && it in 0.0..1.0 } ?: return@withTimeoutOrNull null
            val open = (row as? JsonObject)?.get("open") as? JsonObject
            NoticeDecision(route, importance, row.s("reason"), row.s("by", "model"), open?.s("app")?.ifBlank { null }, open?.s("target")?.ifBlank { null })
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    private fun epoch(at: String) = runCatching { Instant.parse(at).toEpochMilli() }.getOrDefault(nowMs())
    /** Thrown to stop an events batch before its cursor advances, so the event is redelivered and retried. */
    class RetryEvent(message: String) : Exception(message)

    /**
     * A failed side effect is retried (by not acknowledging the event) while it is still fresh: list refreshes
     * for 10 minutes, media commands for 2 minutes (replaying "play" much later would surprise the wearer).
     * Older failures are dropped so one bad event can't block the stream forever.
     */
    private fun require(result: ActionResult, row: JsonElement, maxAgeMs: Long) {
        if (result.ok) return
        if (nowMs() - epoch(row.s("at")) < maxAgeMs) throw RetryEvent(result.message ?: "event action failed")
    }

    suspend fun handleEvent(row: JsonElement) {
        val sequence = row.n("seq").toLong()
        if (sequence > 0 && sequence <= processedSequence) return
        val refresh = 10 * 60_000L
        when (row.s("type")) {
            // Legacy /assistant/open push: never replay a stale one (a screen jumping open minutes later surprises the wearer).
            "open" -> if (row.s("at").isBlank() || nowMs() - epoch(row.s("at")) <= LEGACY_OPEN_MAX_AGE_MS) onOpen(GatewayMappings.decode(row))
            "notification" -> {
                val popup = row.s("route") == "popup" || row.b("popup")
                deliver(Notification(row.s("id"), row.s("source", io.lampyr.glasses.settings.Names.assistant), row.s("icon", "bell"), row.s("title"), row.s("body"), row.n("importance", .5), epoch(row.s("at")),
                    row.jsonObject["open"]?.takeUnless { it == JsonNull }?.let { GatewayMappings.decode(it) }, if (popup) "popup" else "stream", row.s("reason").ifBlank { if (popup) "assistant.popup" else "assistant.stream" }))
            }
            "joy_reply" -> reply(row.s("text"), row.s("sessionId"), row.s("at"), "event", "joy-event:${row.s("seq", row.s("id"))}")
            "doc" -> { require(docs.refresh(), row, refresh); if (!row.b("deleted")) { require(docs.refreshDocument(row.s("id")), row, refresh); addNotification(Notification("doc:${row.s("id")}", io.lampyr.glasses.settings.Names.assistant, "docs", row.s("title", "New document"), "Document received", .25, epoch(row.s("at")), OpenTarget("docs"))) } else notifications.dismiss("doc:${row.s("id")}") }
            "reminder" -> { require(reminders.refresh(), row, refresh); if (row.b("deleted")) notifications.dismiss("reminder:${row.s("id")}") }
            "report" -> require(report.refresh(), row, refresh)
            // Beeper push (gateway WebSocket subscription): refresh the list and any open thread it names; an empty
            // chatIds is a reconcile after reconnect. Not retried: the 20/60 s poll is the floor.
            "beeper" -> pushBeeper(row.a("chatIds").mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            "media" -> require(when (row.s("action")) { "play_youtube" -> media.play("youtube", row.s("videoId", row.s("id"))); "play_spotify" -> media.play("spotify", row.s("uri")); "play_abs" -> media.play("abs", row.s("bookId")); else -> media.control(row.s("action"), row.s("value").toDoubleOrNull()) }, row, 2 * 60_000L)
        }
        if (sequence > 0) { remember("processed-sequence", JsonPrimitive(sequence)); processedSequence = sequence }
    }

    private var memoItems = read<List<Memo>>("memos", emptyList())
    private var recording: PcmCapture? = null
    private var recordingSince = 0.0
    private var memoStoppedReason: String? = null
    private val memoJobs = mutableSetOf<String>()
    private val memoMutex = Mutex()
    private suspend fun updateMemos(transform: (List<Memo>) -> List<Memo>) {
        memoMutex.withLock {
            val next = transform(memoItems)
            cache.put("memos", gatewayJson.encodeToJsonElement(next))
            memoItems = next; changed()
        }
    }
    private suspend fun finishMemo(memo: Memo) {
        if (!memoJobs.add(memo.id)) return
        try {
            var transcript = memo.transcript
            if (transcript.isBlank()) gateway.stream("/voice/dictate?target=memo", withContext(Dispatchers.IO) { File(memo.filePath).readBytes() }, "audio/wav") { row ->
                if (row.s("phase") == "done") transcript = row.s("text")
                if (row.s("phase") == "error") throw GatewayFailure("Memo transcription failed")
            }
            if (transcript.isBlank()) throw GatewayFailure("Memo transcription empty")
            updateMemos { items -> items.map { if (it.id == memo.id) it.copy(transcript = transcript) else it } }
            val title = gateway.write("/voice/title", obj("text" to str(transcript))).s("title", "Voice memo")
            updateMemos { items -> items.map { if (it.id == memo.id) it.copy(title = title, pending = false) else it } }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Pending retained on disk; retried by the memo upload loop and when the memo screen opens. */ }
        finally { memoJobs -= memo.id; work.launch { uploadMemos() } }
    }
    // Every finished memo of 1 s or more goes to the gateway (kind "memo") and on to its delivery targets, with retry.
    private val memoUploader by lazy { MemoUploader(gateway, memoEncoder, nowMs) }
    private val memoUploadLock = Mutex()
    private var memoBackfilled = false
    private val transcribeRetryAt = ConcurrentHashMap<String, Pair<Int, Long>>()
    /** One upload pass over all memos (memos already on the phone included). Safe to call often. */
    internal suspend fun uploadMemos() {
        if (!memoUploadLock.tryLock()) return
        try {
            if (!memoBackfilled) { memoBackfilled = true; backfillMemoFiles() }
            retainMemoAudio()
            for (memo in memoItems.toList()) {
                val current = memoItems.find { it.id == memo.id } ?: continue
                // Wait for the title briefly; a memo whose transcription keeps failing still goes after 5 minutes.
                if (current.uploadDone || current.nextAttemptAt > nowMs() || (current.pending && memoAgeMs(current) < 5 * 60_000)) continue
                val next = memoUploader.step(current) { saved -> updateMemos { items -> items.map { if (it.id == saved.id) it.withUpload(saved) else it } } }
                if (next != current) updateMemos { items -> items.map { if (it.id == next.id) it.withUpload(next) else it } }
            }
        } finally { memoUploadLock.unlock() }
    }
    private fun memoAgeMs(m: Memo) = runCatching { nowMs() - LocalDateTime.parse(m.createdAt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(Long.MAX_VALUE)
    /** Recordings in files/memos that the list lost (for example a cleared cache) are added back so they upload too. */
    private suspend fun backfillMemoFiles() {
        val dir = File(context.filesDir, "memos")
        val known = memoItems.map { File(it.filePath).name }.toSet()
        val orphans = withContext(Dispatchers.IO) { dir.listFiles().orEmpty().filter { it.isFile && it.extension == "wav" && it.name !in known }
            .map { Triple(it, Instant.ofEpochMilli(it.lastModified()), MemoAudio.seconds(it) ?: 0.0) } }
        if (orphans.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val added = orphans.map { (f, modified, seconds) ->
            Memo(f.nameWithoutExtension.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,80}")) } ?: UUID.randomUUID().toString(), "Transcribing…",
                modified.atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a")), seconds.toInt(), filePath = f.absolutePath,
                createdAt = modified.atZone(zone).toLocalDateTime().toString(), pending = true,
                startedAt = modified.minusMillis((seconds * 1000).toLong()).toString())
        }
        updateMemos { items -> (items + added).sortedByDescending { it.createdAt } }
    }
    /** Memo audio is kept until a delivery target has it, then for 30 days; the list entry (title, transcript) stays. */
    private suspend fun retainMemoAudio() = withContext(Dispatchers.IO) {
        memoItems.filter { it.upload == "delivered" && memoAgeMs(it) > 30L * 86_400_000 }.forEach { m ->
            File(m.filePath).delete(); m.uploadPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        }
    }
    /** Failed transcriptions retry by themselves (backing off to an hour), not only when the memo screen opens.
     * After [MEMO_TRANSCRIBE_TRIES] automatic tries (about half a day) a memo whose audio never transcribes stops
     * being re-sent every hour; opening the memo screen still retries it, and it uploads untitled anyway. */
    private fun retryTranscriptions() {
        memoItems.filter { it.pending && it.id !in memoJobs }.forEach { memo ->
            val (tries, at) = transcribeRetryAt[memo.id] ?: (0 to 0L)
            if (tries >= MEMO_TRANSCRIBE_TRIES || nowMs() < at) return@forEach
            transcribeRetryAt[memo.id] = (tries + 1) to nowMs() + minOf(3600_000L, 60_000L shl minOf(tries, 6))
            work.launch { finishMemo(memo) }
        }
    }
    private suspend fun stopMemo(reason: String? = null): ActionResult {
        val source = recording ?: return ActionResult()
        val audio = source.stop(); recording = null; memoStoppedReason = reason; changed()
        val id = UUID.randomUUID().toString()
        val file = File(File(context.filesDir, "memos").apply { mkdirs() }, "$id.wav")
        withContext(Dispatchers.IO) { file.writeBytes(audio) }
        val memo = Memo(id, "Transcribing…", LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a")),
            ((audio.size - 44) / (source.sampleRate * 2)), filePath = file.absolutePath, createdAt = LocalDateTime.now().toString(), pending = true,
            startedAt = Instant.ofEpochMilli(nowMs() - (audio.size - 44).coerceAtLeast(0) * 1000L / (source.sampleRate * 2)).toString())
        updateMemos { listOf(memo) + it }; work.launch { finishMemo(memo) }
        if (reason != null) addNotification(Notification("memo-cap:$id", "Voice memo", "mic", "Memo saved", reason, .7, nowMs(), OpenTarget("memo")))
        return ActionResult(message = reason ?: "Memo saved ${io.lampyr.glasses.ui.fmtS(memo.durationSec)}")
    }
    override val memos = object : MemosRepository {
        // Today's memos, plus any older memo still waiting for its transcript or upload, so a stuck one is visible.
        override val snapshot get() = MemoSnapshot(recording != null, recordingSince, memoItems.filter { it.createdAt.take(10) == LocalDate.now().toString() } +
            memoItems.filter { it.createdAt.take(10) != LocalDate.now().toString() && (it.pending || it.upload == "" || it.upload == "uploading") }, memoStoppedReason)
        override val script get() = memoItems.firstOrNull { it.transcript.isNotBlank() }?.transcript ?: docs.documents.firstOrNull()?.text.orEmpty()
        override suspend fun refresh(): ActionResult { memoItems.filter { it.pending }.forEach { memo -> work.launch { finishMemo(memo) } }; work.launch { uploadMemos() }; changed(); return ActionResult() }
        override suspend fun toggle() = action {
            if (recording == null) {
                if (callState.active) return@action ActionResult.failure("${io.lampyr.glasses.settings.Names.assistant} call active")
                val capture = PcmCapture(context, "memo")
                if (!capture.start(onFull = { work.launch {
                    if (recording === capture) stopMemo("Recording limit reached · memo saved")
                } })) return@action ActionResult.failure("Mic unavailable - open the phone app")
                recording = capture; recordingSince = nowS(); memoStoppedReason = null; changed(); ActionResult()
            } else {
                stopMemo()
            }
        }
    }
    private var phoneStatus = StatusSnapshot()
    /** Connected Bluetooth audio devices by name (requirement 16.4); `status-devices` prefs pick which show. */
    val bluetoothBatteries: BluetoothBatteries = BluetoothBatteries(context, changed = { work.launch { status.refresh() } })
    private fun audioRows(): List<Battery> = bluetoothBatteries.statusRows()
    override val status = object : StatusRepository {
        override val snapshot get() = phoneStatus
        override suspend fun refresh() = action {
            runCatching { bluetoothBatteries.refresh() }
            withTimeoutOrNull(3500) { load("/health/battery") }
            val ring = cache["/health/battery"]?.let { row ->
                val at = runCatching { Instant.parse(row.s("measuredAt")).toEpochMilli() }.getOrNull()
                row.s("level").toDoubleOrNull()?.takeIf { at != null && it in 0.0..1.0 && nowMs() - at in 0..86_399_999 }
            }
            val battery = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val wifi = runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.ssid.trim('"').takeUnless { it == "<unknown ssid>" } }.getOrNull() ?: "Wi-Fi"
            val cell = runCatching { context.getSystemService(TelephonyManager::class.java).signalStrength?.level?.let { "$it/4" } }.getOrNull() ?: "—"
            phoneStatus = StatusSnapshot(listOfNotNull(glassesBattery?.batteryFraction?.let { Battery("glasses", "Glasses", it) }) + audioRows() + listOfNotNull(
                ring?.let { Battery("ring", "Oura", it) },
                battery.takeIf { it in 0..100 }?.let { Battery("phone", "Phone", it / 100.0) }), wifi, cell, if (glassesBattery?.connected == true) "Glasses connected" else "Phone companion")
            changed(); ActionResult()
        }
    }
    override val settings = object : SettingsRepository {
        override val snapshot get() = read("settings", SettingsSnapshot()).let { value ->
            val actual = io.lampyr.glasses.glasses.GlassesTransports.real?.deviceStatusFlow?.value?.brightness
            value.copy(brightness = actual ?: 0, glassesBrightness = actual, brightnessReadOnly = true)
        }
        override suspend fun refresh() = ActionResult()
        override suspend fun update(settings: SettingsSnapshot): ActionResult {
            val brightnessChanged = settings.brightness != snapshot.brightness
            remember("settings", gatewayJson.encodeToJsonElement(settings.copy(brightness = snapshot.brightness)))
            return ActionResult(message = if (brightnessChanged) "Set brightness on the glasses" else null)
        }
    }
    // Start only after every repository and cached list has been initialized, including with immediate fake clients.
    init {
        bluetoothBatteries.start() // Earbuds already connected when the app starts show without waiting for a level change.
        stopCallListener = calls.listen {
            if (callState.active && calls.snapshot.calls.isNotEmpty()) endJoyCall("phone call")
        }
        if (startBackground) {
            work.launch {
                var endRefreshAt = Double.NEGATIVE_INFINITY
                while (isActive) {
                    delay(1000)
                    val remote = read<NowPlaying?>("/spotify/now", null)
                    val now = remote?.let { value -> spotifyAt?.let { GatewayMappings.interpolate(value, nowS() - it) } }
                    if (mediaHub.snapshot == null && now?.source == "spotify" && remote.playing) {
                        changed()
                        if (!now.playing && nowS() - endRefreshAt >= 5) {
                            endRefreshAt = nowS(); load("/spotify/now")
                        }
                    }
                }
            }
            work.launch {
                gateway.events(EventCursor(read("cursor", "")), { cursor -> remember("cursor", JsonPrimitive(cursor)) }) { handleEvent(it) }
            }
            work.launch {
                // Memo transcription retries and uploads run whether or not the glasses display is awake.
                while (isActive) {
                    try { retryTranscriptions(); uploadMemos() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                    delay(60_000)
                }
            }
            work.launch {
                while (isActive) {
                    if (awake) { refreshHome(); t3.refreshThreads(); media.refresh() }
                    delay(60_000)
                }
            }
            work.launch { beeperPoll() }
            work.launch {
                // T3 notifications (watched threads, needs-you anywhere) are pushed by the gateway's notifier as
                // events, dark or locked lens included; only the watch list and the setting shown in the menus refresh here.
                while (isActive) {
                    refreshWatched(); t3.refreshNotifySettings()
                    delay(10 * 60_000)
                }
            }
            work.launch {
                while (isActive) {
                    delay(5_000)
                    if (awake && screen.startsWith("t3")) {
                        t3.refreshThreads()
                        lastThread?.let { if (screen != "t3") t3.refreshThread(it.machine, it.id) }
                    }
                }
            }
        }
    }
    override fun close() { if (callState.active) endJoyCall("service destroyed"); stopCallListener?.invoke(); recording?.stop(); callDictation?.cancel(); calls.close(); mediaHub.close(); bluetoothBatteries.close(); job.cancel() }
}

/** Legacy `open` events older than this are dropped instead of replayed (agent control uses the live link). */
internal const val LEGACY_OPEN_MAX_AGE_MS = 30_000L
/** Phone wait for POST /notify/route (the gateway's classifier call is bounded at 4 s plus a 1.5 s chat resolution). */
internal const val NOTIFY_ROUTE_TIMEOUT_MS = 6_000L
/** A "popup" older than this when it reaches the phone only joins the list (no stale lens wake-ups). */
internal const val POPUP_MAX_AGE_MS = 5 * 60_000L
private const val MEMO_TRANSCRIBE_TRIES = 16
