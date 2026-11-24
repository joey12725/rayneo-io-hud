package io.lampyr.glasses.data

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

private fun JSONArray.strings() = (0 until length()).map { getString(it) }

private fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()

private fun seconds(s: String): Int =
    if (s.contains(':')) s.split(':').let { it[0].toInt() * 60 + it[1].toInt() }
    else
        Regex("(\\d+)([mhdw])").find(s)?.let {
            it.groupValues[1].toInt() *
                mapOf("m" to 60, "h" to 3600, "d" to 86400, "w" to 604800)
                    .getValue(it.groupValues[2])
        } ?: 0

/** Offline implementation. All simulator fixtures and simulated side effects are confined here. */
class FakeDataStore(
    context: Context,
    private val nowS: () -> Double = { android.os.SystemClock.elapsedRealtime() / 1000.0 },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : DataStore {
    override val calls = io.lampyr.glasses.calls.FakeCalls(nowS, nowMs)
    private val seed =
        JSONObject(context.assets.open("demo.json").bufferedReader().use { it.readText() })
    private val listeners = linkedSetOf<() -> Unit>()
    private var activeScreen = "home"
    private var counter = 100L

    private fun nextId() = (counter++).toString()

    private fun changed() {
        listeners.toList().forEach { it() }
    }

    private fun ok(message: String? = null): ActionResult {
        changed()
        return ActionResult(message = message)
    }

    private fun clock() =
        DateTimeFormatter.ofPattern("h:mm a", Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(nowMs()))

    override val home = HomeSnapshot(nextEvent = NextEvent("Team standup", "8:00 PM", "Zoom"))

    override fun addChangeListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    override fun onScreenChanged(screen: String) {
        activeScreen = screen
    }

    override suspend fun refreshHome() = ok()

    override fun close() {
        scope.cancel()
    }

    private val samples = mutableMapOf<String, Int>()

    override fun dictationSample(target: String): String {
        val n = samples.getOrDefault(target, 0)
        samples[target] = n + 1
        return when {
            target == "joy" ->
                seed.getJSONArray("JOY_SAMPLES").let { it.getString(n % it.length()) }
            target == "t3" ->
                "Pause after the tests finish and give me a short summary of anything that failed."
            target == "new-thread" ->
                "Add a share-intent upload from the voice memo app to the gateway, with a retry queue and a notification when each upload finishes."
            target == "other" ->
                "Use the device token, but keep a session-cookie fallback behind a flag."
            target == "reminder" -> "Ask how the Saturday game went."
            target == "raw" -> "Sam" // raw words (no cleanup): the Beeper "find a chat" and Phone "call who?" lookups
            else -> seed.getJSONArray("SAMPLE_MSG").let { it.getString(n % it.length()) }
        }
    }

    override val joy =
        object : JoyRepository {
            override var snapshot =
                JoyState(text = seed.getJSONObject("joy").getString("out"), since = nowS())
                private set

            private var turn = 0
            private var job: Job? = null
            private var callJob: Job? = null
            override var call = JoyCallState(); private set

            private fun status(st: String, anim: String = io.lampyr.glasses.render.avatar.AvatarStates.forStatus(st)) {
                snapshot = snapshot.copy(status = st, anim = anim, since = nowS())
                changed()
            }

            override suspend fun refresh() = ok()

            override suspend fun startVoiceCall(): ActionResult {
                callJob?.cancel()
                call = JoyCallState(active = true)
                status("listening")
                callJob =
                    scope.launch {
                        while (isActive) {
                            delay(4000)
                            status("speaking")
                            delay(4000)
                            status("listening")
                        }
                    }
                return ok()
            }

            override suspend fun muteVoiceCall(): ActionResult {
                call = call.copy(muted = !call.muted); return ok()
            }

            override suspend fun endVoiceCall(): ActionResult {
                callJob?.cancel()
                callJob = null
                call = call.copy(active = false, endedReason = "user")
                status("idle")
                return ok()
            }

            override suspend fun setListening(listening: Boolean): ActionResult {
                status(if (listening) "listening" else "idle")
                return ActionResult()
            }

            override suspend fun newConversation(): ActionResult {
                job?.cancel()
                snapshot =
                    JoyState(
                        sessionId = nextId(),
                        text = "New conversation. What do you need?",
                        since = nowS(),
                    )
                return ok()
            }

            override suspend fun send(message: String, quiet: Boolean): ActionResult {
                job?.cancel()
                val n = turn++ % 3
                snapshot = snapshot.copy(prompt = message)
                status("thinking")
                job =
                    scope.launch {
                        delay(900)
                        status(if (n == 2) "remembering" else "searching")
                        delay(1400)
                        val answer = seed.getJSONArray("JOY_ANS").getString(n)
                        snapshot = snapshot.copy(text = answer, prompt = null)
                        status("speaking")
                        if (activeScreen != "joy")
                            notificationItems =
                                listOf(
                                    Notification(
                                        nextId(),
                                        "joy",
                                        "joy",
                                        "${io.lampyr.glasses.settings.Names.assistant} replied",
                                        answer,
                                        .85,
                                        nowMs(),
                                        OpenTarget("joy"),
                                    )
                                ) + notificationItems
                        changed()
                        delay(1150)
                        status("idle", if (n == 0) "happy" else "idle")
                        if (n == 0) {
                            delay(1300)
                            if (snapshot.anim == "happy") status("idle")
                        }
                    }
                return ActionResult()
            }
        }
    private val messageMap = linkedMapOf<String, List<Message>>()
    private var chatItems =
        seed.getJSONArray("chats").objects().mapIndexed { i, c ->
            val id = "chat-$i"
            val msgs =
                c.getJSONArray("msgs").objects().mapIndexed { j, m ->
                    Message(
                        "$id-$j",
                        m.getString("from"),
                        text = m.getString("t"),
                        reactions = m.array("rx").strings().map { Reaction(it) },
                        approval = m.optBoolean("approval"),
                        at = m.optString("at"),
                    )
                }
            messageMap[id] = msgs
            Chat(id, c.getString("name"), c.getString("net"), c.getInt("unread"), msgs.lastOrNull())
        }
    override val beeper =
        object : BeeperRepository {
            override val chats
                get() = chatItems

            override fun messages(chatId: String) = messageMap[chatId].orEmpty()

            override suspend fun refreshChats() = ok()

            override suspend fun refreshMessages(chatId: String) = ok()

            override suspend fun markRead(chatId: String): ActionResult {
                chatItems = chatItems.map { if (it.id == chatId) it.copy(unread = 0) else it }
                return ok()
            }

            override suspend fun send(
                chatId: String,
                text: String,
                replyTo: String?,
            ): ActionResult {
                val c =
                    chats.find { it.id == chatId }
                        ?: return ActionResult.failure("Chat unavailable")
                val reply = messages(chatId).find { it.id == replyTo }
                val m =
                    Message(
                        nextId(),
                        "You",
                        true,
                        text,
                        replyTo = reply?.let { ReplyTo(it.sender, it.text) },
                        at = clock(),
                    )
                messageMap[chatId] = messages(chatId) + m
                chatItems = chatItems.map { if (it.id == chatId) it.copy(last = m) else it }
                return ok("Sent · ${c.network}")
            }

            override suspend fun react(
                chatId: String,
                messageId: String,
                key: String,
                remove: Boolean,
            ): ActionResult {
                val m =
                    messages(chatId).find { it.id == messageId }
                        ?: return ActionResult.failure("Message unavailable")
                val reactions =
                    m.reactions.filterNot { it.mine } +
                        (if (remove) emptyList() else listOf(Reaction(key, mine = true)))
                messageMap[chatId] =
                    messages(chatId).map {
                        if (it.id == messageId) it.copy(reactions = reactions) else it
                    }
                return ok(
                    if (m.approval && key == "✅") "Approved · ${io.lampyr.glasses.settings.Names.assistant} notified"
                    else if (m.approval && key == "❌") "Cancelled · ${io.lampyr.glasses.settings.Names.assistant} notified" else "Reacted"
                )
            }

            override suspend fun setSpam(chatId: String, spam: Boolean): ActionResult {
                chatItems = chatItems.map { if (it.id == chatId) it.copy(spam = spam, spamSource = "user") else it }
                return ok(if (spam) "Marked spam" else "Marked not spam")
            }

            override suspend fun setPin(chatId: String, pinned: Boolean): ActionResult {
                chatItems = chatItems.map { if (it.id == chatId) it.copy(glassesPinned = pinned) else it }
                return ok(if (pinned) "Pinned on glasses" else "Unpinned")
            }
        }
    private val t3seed = seed.getJSONObject("T3")
    private val details = linkedMapOf<ThreadKey, ThreadDetail>()

    private fun makeThread(j: JSONObject, i: Int, settled: Boolean = false): Thread {
        val machine = if (i == 1) "vps" else "desktop"
        val id = if (settled) "settled-$i" else j.getInt("id").toString()
        val now =
            j.optJSONObject("now")?.let {
                Step(
                    it.getString("kind"),
                    it.getString("what"),
                    elapsedSec = it.getDouble("secs"),
                    quietSec = if (it.has("quiet")) it.getDouble("quiet") else null,
                    since = nowS(),
                )
            }
        val th =
            Thread(
                machine,
                id,
                j.getString("title"),
                j.getString("proj"),
                j.optString("prov", "Claude Code"),
                if (settled) "settled" else j.getString("st"),
                seconds(j.getString("age")).toLong(),
                step = now,
            )
        val req =
            j.optJSONObject("req")?.let { r ->
                val kind = r.getString("type")
                Request(
                    "request-$id",
                    kind,
                    if (kind == "approval") emptyList()
                    else
                        listOf(
                            Question(
                                "q1",
                                question = r.getString("q"),
                                options = r.getJSONArray("opts").strings().map { Option(it) },
                                multi = kind == "multi",
                                selected =
                                    (0 until r.array("sel").length()).map {
                                        r.array("sel").getInt(it)
                                    },
                            )
                        ),
                    if (kind == "approval")
                        Approval(r.getString("kind"), r.getString("cmd"), r.getString("why"))
                    else null,
                )
            }
        val latest =
            if (settled)
                "Settled. Final message: ${th.title} is merged and the tests pass. Nothing left to do unless you reopen it."
            else j.optString("final", j.optString("msg", j.optString("last", "Working.")))
        val history =
            listOf(
                HistoryEntry(
                    "user",
                    "Work on: ${th.title}. Keep changes small and run the tests before you stop.",
                ),
                HistoryEntry(
                    "agent",
                    "Plan:\n1. Read the existing code and tests.\n2. Make the change behind a flag.\n3. Run the suite and report back.",
                ),
                HistoryEntry(
                    "agent",
                    "Found the old config value: RETRY_LIMIT = 4 in src/config.ts. I kept it, and added a backoff of 250 ms doubling per attempt.",
                ),
                HistoryEntry("user", "Good. Keep the 250 ms value but cap it at 4 seconds."),
                HistoryEntry("agent", latest),
            )
        val steps =
            (0 until j.array("steps").length()).map {
                j.array("steps").getJSONArray(it).let { a ->
                    RecentStep(a.getString(0), seconds(a.getString(1)))
                }
            }
        details[th.key] =
            ThreadDetail(th, Latest(latest), history, now?.let { Working(it, steps) }, req)
        return th
    }

    private var threads =
        t3seed.getJSONArray("threads").objects().mapIndexed { i, j -> makeThread(j, i) }
    private var settledThreads =
        t3seed.getJSONArray("settled").objects().mapIndexed { i, j -> makeThread(j, i, true) }
    private var usages =
        t3seed.getJSONArray("usage").objects().mapIndexed { i, j ->
            Usage(
                j.getString("prov"),
                "desktop",
                "instance-$i",
                j.getJSONArray("wins").objects().map { w ->
                    UsageWindow(
                        w.getString("label"),
                        w.getInt("pct"),
                        w.getString("reset"),
                        pace = w.getString("pace"),
                        elapsedFraction = w.getDouble("tick"),
                    )
                },
                j.array("credits").objects().mapIndexed { k, c ->
                    Credit("credit-$k", c.getString("label"), c.getString("desc"), c.getInt("n"))
                },
            )
        }

    private fun updateDetail(d: ThreadDetail) {
        details[d.thread.key] = d
        threads = threads.map { if (it.key == d.thread.key) d.thread else it }
        settledThreads = settledThreads.map { if (it.key == d.thread.key) d.thread else it }
        changed()
    }

    private fun start(
        d: ThreadDetail,
        kind: String,
        what: String,
        message: String,
        recent: List<RecentStep>,
    ): ThreadDetail {
        val step = Step(kind, what, since = nowS())
        return d.copy(
            thread = d.thread.copy(status = "working", step = step),
            working = Working(step, recent),
            latest = Latest(message),
            request = null,
        )
    }

    override val t3 =
        object : T3Repository {
            override val threads
                get() = this@FakeDataStore.threads

            override val settled
                get() = settledThreads

            override val usage
                get() = usages

            override val environments =
                t3seed.getJSONArray("envs").objects().map {
                    Environment(
                        it.getString("n"),
                        it.getString("n"),
                        it.getBoolean("on"),
                        it.getString("os"),
                        it.getString("seen"),
                    )
                }
            override val projects =
                t3seed.getJSONArray("projects").strings().map { Project("desktop", it, it) }

            override fun detail(machine: String, id: String) = details[ThreadKey(machine, id)]

            override suspend fun refreshThreads() = ok()

            override suspend fun refreshThread(machine: String, id: String) = ok()

            override suspend fun refreshSettled() = ok()

            override suspend fun refreshUsage() = ok()

            override suspend fun refreshEnvironments() = ok()

            override suspend fun refreshProjects() = ok()

            override suspend fun visit(machine: String, id: String) = ok()

            override suspend fun send(
                machine: String,
                id: String,
                text: String,
                mode: String,
            ): ActionResult {
                val d = detail(machine, id) ?: return ActionResult.failure("Thread unavailable")
                val history = d.history + HistoryEntry("user", text)
                if (d.thread.status == "working" && mode == "queue") {
                    updateDetail(d.copy(history = history))
                    return ok("Queued · sends after this turn")
                }
                val steer = mode == "steer"
                updateDetail(
                    start(
                            d,
                            "Thinking",
                            if (steer) "Steering: reading your message" else "Reading your message",
                            text,
                            listOf(RecentStep("You${if(steer)" (steer)" else ""}: $text", 0)) +
                                (if (steer) d.working?.recent.orEmpty() else emptyList()),
                        )
                        .copy(history = history)
                )
                return ok(if (steer) "Steered with /glasses" else "Sent with /glasses")
            }

            override suspend fun respond(
                machine: String,
                id: String,
                requestId: String,
                decision: String?,
                answers: Map<String, List<String>>,
            ): ActionResult {
                val d = detail(machine, id) ?: return ActionResult.failure("Thread unavailable")
                if (d.request?.id != requestId) return ActionResult.failure("Request expired")
                val approval = d.request.approval
                if (approval != null) {
                    if (decision == "decline")
                        updateDetail(
                            d.copy(
                                thread = d.thread.copy(status = "done"),
                                latest =
                                    Latest(
                                        "You denied the command. The thread is waiting for new instructions."
                                    ),
                                request = null,
                            )
                        )
                    else
                        updateDetail(
                            start(
                                d,
                                "Running command",
                                approval.command,
                                "Running the approved command.",
                                listOf(RecentStep("Approved by you", 0)),
                            )
                        )
                    return ok(if (decision == "decline") "Denied" else "Approved")
                }
                val a = answers.values.flatten().joinToString(", ")
                updateDetail(
                    start(
                            d,
                            "Thinking",
                            "Applying your answer",
                            "Got it: $a.",
                            listOf(RecentStep("You answered: $a", 0)),
                        )
                        .copy(history = d.history + HistoryEntry("user", a))
                )
                return ok("Answer sent")
            }

            override suspend fun newThread(
                machine: String,
                projectId: String,
                text: String,
            ): ActionResult {
                val th =
                    Thread(
                        machine,
                        nextId(),
                        text.split(' ').take(4).joinToString(" "),
                        projectId,
                        "Claude Code",
                        "working",
                        step = Step("Thinking", "Reading the repo", since = nowS()),
                    )
                details[th.key] =
                    ThreadDetail(
                        th,
                        Latest(text),
                        listOf(HistoryEntry("user", text)),
                        Working(th.step),
                    )
                this@FakeDataStore.threads = listOf(th) + threads
                return ok("Thread started").copy(id = th.id)
            }

            override suspend fun resetUsage(
                machine: String,
                instanceId: String,
                creditId: String,
            ): ActionResult {
                val u =
                    usages.find { it.machine == machine && it.instanceId == instanceId }
                        ?: return ActionResult.failure("Usage unavailable")
                val c =
                    u.credits.find { it.creditId == creditId && it.availableCount > 0 }
                        ?: return ActionResult.failure("No banked resets")
                usages =
                    usages.map {
                        if (it != u) it
                        else
                            it.copy(
                                credits =
                                    it.credits
                                        .map { z ->
                                            if (z == c)
                                                z.copy(availableCount = z.availableCount - 1)
                                            else z
                                        }
                                        .filter { z -> z.availableCount > 0 },
                                windows =
                                    it.windows.map { w ->
                                        if (
                                            if (c.label.startsWith("Fable"))
                                                w.label.contains("Fable")
                                            else
                                                !w.label.contains("Fable") &&
                                                    w.label != "Session (5-hour)"
                                        )
                                            w.copy(usedPercent = 0)
                                        else w
                                    },
                            )
                    }
                return ok(c.label + " used")
            }

            override val watched: Set<String> get() = watchedThreads

            override suspend fun watch(machine: String, id: String, title: String, on: Boolean): ActionResult {
                watchedThreads = if (on) watchedThreads + "$machine:$id" else watchedThreads - "$machine:$id"
                return ok(if (on) "Watching: you'll be notified" else "Stopped watching")
            }

            override val notifyAllNeeds: Boolean get() = notifyAllNeedsSetting

            override suspend fun setNotifyAllNeeds(on: Boolean): ActionResult {
                notifyAllNeedsSetting = on
                return ok(if (on) "Notifying: any thread needing you" else "Notifying: watched threads only")
            }
        }
    /** Demo/test: "Notify for all threads needing me". */
    var notifyAllNeedsSetting = true
    /** Demo/test: T3 threads watched from the glasses. */
    var watchedThreads: Set<String> = emptySet()
    private var playing =
        seed.getJSONObject("M").let {
            NowPlaying(
                it.getString("src"),
                it.getString("title"),
                subtitle = it.getString("sub"),
                positionSec = .34 * 58200,
                durationSec = 58200.0,
                playing = true,
                device = it.getString("dev"),
                speed = 1.5,
                elapsedLabel = it.getString("el"),
                durationLabel = it.getString("len"),
            )
        }
    private val books =
        seed.getJSONArray("ABS").objects().mapIndexed { i, b ->
            Book("book-$i", b.getString("t"), b.getString("a"), b.getString("ch"), b.getDouble("p"))
        }
    private var deviceItems =
        seed.getJSONArray("DEVICES").strings().map { Device(it, it, active = it == playing.device) }
    private val videoMap =
        seed.getJSONObject("YT_LISTS").let { lists ->
            val channels = seed.getJSONArray("CHANNELS").strings()
            lists.keys().asSequence().associateWith { key ->
                lists.getJSONArray(key).strings().mapIndexed { i, v ->
                    Video(
                        "$key-$i",
                        v,
                        channels[(v.length * 7 + i) % channels.size],
                        (v.length % 17 + 4) * 60 + (v.length * 13) % 60,
                    )
                }
            }
        }
    override val media =
        object : MediaRepository {
            override val current get() = if (playing.source == "spotify") playing else
                NowPlaying(source = "spotify", title = "Midnight City", artist = "M83", device = playing.device)
            override val nowPlaying
                get() = playing

            override val books
                get() = this@FakeDataStore.books

            override val playlists =
                (listOf("Discover Weekly", "Liked Songs") +
                        seed.getJSONArray("SPOT_RECENT").strings())
                    .map {
                        Playlist(
                            it,
                            it,
                            if (it == "Currently playing") "Midnight City · M83" else null,
                        )
                    }
            override val devices
                get() = deviceItems

            override val youtubeLists
                get() = videoMap.keys.toList()

            override fun videos(key: String) = videoMap[key].orEmpty()

            override suspend fun refresh() = ok()

            override suspend fun refreshVideos(key: String) = ok()

            override suspend fun control(action: String, value: Double?): ActionResult {
                playing =
                    when (action) {
                        "play" -> playing.copy(playing = true)
                        "pause" -> playing.copy(playing = false)
                        "toggle" -> playing.copy(playing = !playing.playing)
                        "volume" ->
                            playing.copy(
                                volume =
                                    (value ?: playing.volume.toDouble()).toInt().coerceIn(0, 15)
                            )
                        "speed" -> playing.copy(speed = value ?: 1.0)
                        "seek" ->
                            playing.copy(
                                positionSec =
                                    (playing.positionSec + (value ?: 0.0)).coerceIn(
                                        0.0,
                                        playing.durationSec,
                                    )
                            )
                        else -> playing
                    }
                return ok(
                    when (action) {
                        "next" -> "Next track"
                        "previous" -> "Previous track"
                        else -> null
                    }
                )
            }

            override suspend fun play(source: String, id: String): ActionResult {
                playing =
                    when (source) {
                        "abs" -> {
                            val b =
                                books.find { it.id == id }
                                    ?: return ActionResult.failure("Book unavailable")
                            playing.copy(
                                source = source,
                                title = b.title,
                                subtitle = b.author + " · " + b.chapter,
                                playing = true,
                                positionSec = b.progress * b.durationSec,
                                durationSec = b.durationSec,
                                elapsedLabel = "resume",
                                durationLabel = "16h 10m",
                            )
                        }
                        "spotify" -> {
                            val pl = if (id == "Currently playing") "Discover Weekly" else id
                            val pair = seed.getJSONObject("SPOT_TRACK").getJSONArray(pl)
                            playing.copy(
                                source = source,
                                title = pair.getString(0),
                                artist = pair.getString(1),
                                subtitle = pair.getString(1) + " · " + pl,
                                playing = true,
                                positionSec = 0.0,
                                durationSec = 243.0,
                                elapsedLabel = "0:00",
                                durationLabel = "4:03",
                            )
                        }
                        else -> {
                            val v =
                                videoMap.values.flatten().find { it.videoId == id }
                                    ?: return ActionResult.failure("Video unavailable")
                            playing.copy(
                                source = "youtube",
                                title = v.title,
                                subtitle = v.channel,
                                playing = true,
                                positionSec = 0.0,
                                durationSec = v.durationSec.toDouble(),
                                elapsedLabel = "0:00",
                                durationLabel = null,
                            )
                        }
                    }
                return ok(if (source == "spotify") "Playing on ${playing.device}" else "Playing")
            }

            override suspend fun transfer(deviceId: String): ActionResult {
                playing = playing.copy(device = deviceId)
                deviceItems = deviceItems.map { it.copy(active = it.id == deviceId) }
                return ok("Moved to $deviceId")
            }
        }
    override val calendar =
        object : CalendarRepository {
            override val dateLabel = "Wed Nov 11"
            override val agenda =
                seed.getJSONArray("CAL").objects().mapIndexed { i, d ->
                    CalendarDay(
                        d.getString("day"),
                        "2026-11-${11+i}",
                        (0 until d.getJSONArray("items").length()).map { k ->
                            val a = d.getJSONArray("items").getJSONArray(k)
                            CalendarEvent(
                                "event-$i-$k",
                                a.getString(1),
                                a.getString(0),
                                location = a.getString(2),
                                notes = "No notes. A reminder comes 15 minutes before.",
                            )
                        },
                    )
                }

            override suspend fun refresh() = ok()
        }
    override val people =
        object : PeopleRepository {
            private val people =
                seed.getJSONArray("PEOPLE").objects().mapIndexed { i, p ->
                    Person(
                        "person-$i",
                        p.getString("n"),
                        p.getString("where"),
                        p.getString("seen"),
                        p.getString("notes"),
                    )
                }
            override var snapshot = PeopleSnapshot(people.take(2), people)
            override suspend fun mute(personId: String, muted: Boolean): ActionResult {
                snapshot = snapshot.copy(heard = snapshot.heard.filterNot { it.id == personId && muted },
                    recent = snapshot.recent.map { if (it.id == personId) it.copy(muted = muted) else it })
                changed()
                return ok()
            }

            override suspend fun refresh() = ok()

            override suspend fun remind(personId: String, text: String): ActionResult {
                armedReminders = armedReminders + Reminder(nextId(), text, ReminderCondition("person", personId),
                    Instant.ofEpochMilli(nowMs()).plusSeconds(30 * 86400L).toString())
                return ok("Reminder armed")
            }
        }
    private var armedReminders = emptyList<Reminder>()
    override val reminders = object : RemindersRepository {
        override val armed get() = armedReminders
        override suspend fun refresh() = ok()
        override suspend fun cancel(id: String): ActionResult {
            armedReminders = armedReminders.filterNot { it.id == id }
            return ok()
        }
    }
    override val health =
        object : HealthRepository {
            override val updatedLabel = "Oura · 9m ago"
            override val metrics =
                seed.getJSONArray("HEALTH").objects().map { m ->
                    Metric(
                        m.getString("k").let { if (it == "ready") "readiness" else it },
                        m.getString("name"),
                        m.getString("val"),
                        m.getString("when"),
                        (0 until m.getJSONArray("data").length()).map {
                            Sample("day-$it", m.getJSONArray("data").getDouble(it))
                        },
                        breakdown =
                            (0 until m.getJSONArray("bd").length()).map {
                                val a = m.getJSONArray("bd").getJSONArray(it)
                                val v = a.get(1)
                                Breakdown(
                                    a.getString(0),
                                    if (v is String) v else null,
                                    if (v is Number) v.toDouble()
                                    else if (a.length() > 2) a.getDouble(2) else null,
                                )
                            },
                        icon = m.getString("ic"),
                    )
                }

            override suspend fun refresh() = ok()
        }
    private var panel =
        HomeAssistantPanel(
            listOf(
                Scene("people", "People over", "people"),
                Scene("warm", "Warm white", "bulb"),
                Scene("sleep", "Going to sleep", "moon"),
            ),
            printers =
                listOf(
                    Printer(
                        "p1s",
                        "Bambu P1S",
                        "62% · 41m",
                        .62,
                        2460,
                        detail =
                            "Printing bracket_v3.gcode\n62% done, 41 min left\nNozzle 215°C · bed 60°C\nAMS slot 2: PETG black",
                    ),
                    Printer(
                        "a1",
                        "Bambu A1 mini",
                        "Idle",
                        detail = "Idle. Last job: cable_clip x12 finished 2h ago.",
                    ),
                    Printer(
                        "resin",
                        "Resin printer",
                        "Layer 812/1400",
                        layer = 812,
                        layers = 1400,
                        detail =
                            "Printing mini_set_02\nLayer 812 of 1400, 1h 05m left\nResin vat 38%",
                    ),
                ),
        )
    override val ha =
        object : HaRepository {
            override val panel
                get() = this@FakeDataStore.panel

            override suspend fun refresh() = ok()

            override suspend fun scene(id: String) =
                ok(panel.scenes.find { it.id == id }?.name + " · done")

            override suspend fun alarm(time: String?, style: String?): ActionResult {
                this@FakeDataStore.panel =
                    panel.copy(
                        alarm =
                            panel.alarm.copy(
                                time = time ?: panel.alarm.time,
                                style = style ?: panel.alarm.style,
                            )
                    )
                return ok("Alarm " + (time ?: style))
            }
        }
    override val docs =
        object : DocsRepository {
            override var documents =
                seed.getJSONArray("DOCS").objects().mapIndexed { i, d ->
                    Document("doc-$i", d.getString("t"), d.getString("d"))
                }
                private set

            override suspend fun refresh() = ok()

            override suspend fun refreshDocument(id: String) = ok()

            override suspend fun delete(id: String): ActionResult {
                documents = documents.filterNot { it.id == id }
                return ok("Deleted")
            }
        }
    override val report =
        object : ReportRepository {
            override val snapshot =
                ReportSnapshot(
                    "Wed Nov 11",
                    cards =
                        seed.getJSONArray("REPORT").objects().map {
                            ReportCard(
                                it.getString("ic"),
                                it.getString("ic"),
                                it.getString("h"),
                                it.getString("b"),
                            )
                        },
                )

            override suspend fun refresh() = ok()
        }
    private var notificationItems =
        seed.getJSONObject("S").getJSONArray("notifs").objects().map { n ->
            Notification(
                nextId(),
                n.getString("ic"),
                n.getString("ic"),
                n.getString("title"),
                n.getString("body"),
                n.getDouble("imp"),
                nowMs() - (System.currentTimeMillis() - n.getLong("ts")),
                null,
            )
        }

    init {
        // Seed Date.now() values are regenerated from the simulator; use relative fixture ages.
        notificationItems =
            listOf(
                Notification(
                    "n1",
                    "beeper",
                    "beeper",
                    "Sam",
                    "bring the speaker if you can 🙏",
                    .7,
                    nowMs() - 28 * 60000,
                    OpenTarget("beeper-thread", mapOf("chatId" to "chat-0")),
                ),
                Notification(
                    "n2",
                    "t3",
                    "t3",
                    "T3 · Root page copy pass",
                    "Done: updated the root page copy",
                    .5,
                    nowMs() - 25 * 60000,
                    OpenTarget("t3"),
                ),
                Notification(
                    "n3",
                    "joy",
                    "joy",
                    "${io.lampyr.glasses.settings.Names.assistant}",
                    "Morning report is ready",
                    .35,
                    nowMs() - 14 * 3600000,
                    OpenTarget("report"),
                ),
            )
    }

    override val notifications =
        object : NotificationsRepository {
            override val items
                get() = notificationItems

            override suspend fun refresh() = ok()

            override suspend fun dismiss(id: String): ActionResult {
                notificationItems = items.filterNot { it.id == id }
                return ok()
            }

            override suspend fun clear(): ActionResult {
                notificationItems = emptyList()
                return ok("All cleared")
            }

            override fun listenPopups(listener: (Notification) -> Unit): () -> Unit {
                popupListeners += listener
                return { popupListeners -= listener }
            }
        }
    private val popupListeners = linkedSetOf<(Notification) -> Unit>()

    /** Demo/test: deliver a routed notification (stream joins the list; popup also tells the UI). */
    fun deliverNotification(n: Notification) {
        if (n.route == "suppress") return
        notificationItems = listOf(n) + notificationItems.filterNot { it.id == n.id }
        changed()
        if (n.route == "popup") popupListeners.toList().forEach { it(n) }
    }
    private var memoState =
        MemoSnapshot(
            list =
                seed.getJSONObject("S").getJSONObject("memo").getJSONArray("list").objects().map {
                    Memo(
                        nextId(),
                        it.getString("title"),
                        it.getString("at"),
                        seconds(it.getString("dur")),
                        upload = it.optString("upload", ""), delivered = it.optString("upload", "") == "delivered",
                        deliveredTo = if (it.optString("upload", "") == "delivered") listOf("Notes") else emptyList(),
                    )
                }
        )
    override val memos =
        object : MemosRepository {
            override val snapshot
                get() = memoState

            override val script = seed.getString("SCRIPT")
            private var titleIndex = 0

            override suspend fun refresh() = ok()

            override suspend fun toggle(): ActionResult {
                if (snapshot.recording) {
                    val s = floor(nowS() - snapshot.since).toInt()
                    val id = nextId()
                    memoState =
                        snapshot.copy(
                            recording = false,
                            list = listOf(Memo(id, "Transcribing…", clock(), s)) + snapshot.list,
                        )
                    scope.launch {
                        delay(1600)
                        val titles = seed.getJSONArray("MEMO_TITLES")
                        val title = titles.getString(titleIndex++ % titles.length())
                        memoState =
                            memoState.copy(
                                list =
                                    memoState.list.map {
                                        if (it.id == id) it.copy(title = title, upload = "delivered", delivered = true, deliveredTo = listOf("Notes")) else it
                                    }
                            )
                        changed()
                    }
                    return ok("Memo saved ${io.lampyr.glasses.ui.fmtS(s)}")
                }
                memoState = snapshot.copy(recording = true, since = nowS())
                return ok()
            }
        }
    override val status =
        object : StatusRepository {
            override val snapshot =
                StatusSnapshot(
                    listOf(
                        Battery("glasses", "Glasses", .82),
                        Battery("phone", "Phone", .64),
                        Battery("ring", "Oura ring", .4),
                        Battery("buds", "Life P2", .7),
                    ),
                    "Home-5G",
                    "LTE · 3 bars",
                )

            override suspend fun refresh() = ok()
        }
    override val settings =
        object : SettingsRepository {
            override var snapshot = SettingsSnapshot(glassesBrightness = 8, brightnessReadOnly = true)
                private set

            override suspend fun refresh() = ok()

            override suspend fun update(settings: SettingsSnapshot): ActionResult {
                snapshot = settings
                return ok()
            }
        }

    init {
        // The simulator advances non-Spotify media by this fraction every 120 ms.
        // A frozen injected clock also freezes this job for deterministic screenshots.
        var previous = nowS()
        scope.launch {
            while (isActive) {
                delay(120)
                val now = nowS()
                if (now > previous && playing.playing && playing.source != "spotify") {
                    playing =
                        playing.copy(
                            positionSec =
                                min(
                                    playing.durationSec,
                                    playing.positionSec +
                                        .00002 * playing.speed * playing.durationSec,
                                )
                        )
                    changed()
                }
                previous = now
            }
        }
    }
}
