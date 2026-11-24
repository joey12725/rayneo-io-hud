package io.lampyr.glasses.data

/**
 * Cached, immutable snapshots: reading any property must never do I/O. Actions run off draw().
 * Implementations publish a new snapshot before calling change listeners on the UI dispatcher.
 * Refresh failures leave the last good snapshot intact and return a failure for a toast.
 */
interface DataStore {
    val reminders: RemindersRepository get() = EmptyReminders
    val calls: io.lampyr.glasses.calls.Calls
    val home: HomeSnapshot
    val joy: JoyRepository
    val beeper: BeeperRepository
    val t3: T3Repository
    val media: MediaRepository
    val calendar: CalendarRepository
    val people: PeopleRepository
    val health: HealthRepository
    val ha: HaRepository
    val docs: DocsRepository
    val report: ReportRepository
    val notifications: NotificationsRepository
    val memos: MemosRepository
    val status: StatusRepository
    val settings: SettingsRepository

    /** Returns an unsubscribe function; supports UI and transport consumers simultaneously. */
    fun addChangeListener(listener: () -> Unit): () -> Unit

    fun onScreenChanged(screen: String) {}

    suspend fun refreshHome(): ActionResult

    /** Fake dictation fixture source; live dictation supplies actual transcribed audio instead. */
    fun dictationSample(target: String): String = ""

    fun close() {}
}

interface JoyRepository {
    val snapshot: JoyState
    val call: JoyCallState get() = JoyCallState()

    suspend fun refresh(): ActionResult

    suspend fun setListening(listening: Boolean): ActionResult
    suspend fun dictationPhase(phase: String): ActionResult = setListening(phase == "listening")

    suspend fun send(message: String, quiet: Boolean = false): ActionResult

    suspend fun newConversation(): ActionResult

    suspend fun startVoiceCall(): ActionResult = ActionResult.failure("Voice call unavailable")

    suspend fun endVoiceCall(): ActionResult = ActionResult()
    suspend fun muteVoiceCall(): ActionResult = ActionResult.failure("Mute unavailable")
}

interface BeeperRepository {
    val chats: List<Chat>
    /** Unread CONVERSATIONS (not messages), excluding muted (unless @mentioned) and spam chats (BEEP-01/02). */
    val unreadTotal: Int get() = chats.count { it.countsTowardUnread }
    val unreadChats: Int get() = chats.count { it.countsTowardUnread }
    fun pageState(chatId: String): MessagePageState = MessagePageState(loaded = messages(chatId).isNotEmpty())
    suspend fun loadOlder(chatId: String): ActionResult = ActionResult()

    fun messages(chatId: String): List<Message>

    suspend fun refreshChats(): ActionResult

    suspend fun refreshMessages(chatId: String): ActionResult

    suspend fun markRead(chatId: String): ActionResult

    suspend fun send(chatId: String, text: String, replyTo: String? = null): ActionResult

    /** Send with spoken mentions; the gateway converts each token to the platform's mention form. */
    suspend fun send(chatId: String, text: String, replyTo: String?, mentions: List<Mention>): ActionResult = send(chatId, text, replyTo)

    suspend fun react(
        chatId: String,
        messageId: String,
        key: String,
        remove: Boolean = false,
    ): ActionResult

    /** More chats exist than are loaded; the list shows a trailing "Load more" row. */
    val hasMoreChats: Boolean get() = false
    suspend fun loadMoreChats(): ActionResult = ActionResult()
    /** Per-chat spam override, stored in the gateway only (never written to Beeper). */
    suspend fun setSpam(chatId: String, spam: Boolean): ActionResult = ActionResult.failure("Spam marking unavailable")
    /** A thread screen for [chatId] is on the stack, so pushes and polls refresh its messages. */
    fun threadVisible(chatId: String, visible: Boolean) {}

    // ---- BEEP-PRI: list order, glasses pins and spoken find ----
    /** A chat by ID: a loaded row, or one a find returned from beyond the loaded page. */
    fun chat(chatId: String): Chat? = chats.find { it.id == chatId }
    /** The order the list asks the gateway for (setting beeper.order); a change refreshes the list. */
    fun useOrder(order: BeeperOrder) {}
    /** Pin or unpin a chat on the glasses (stored in the gateway, so the assistant sees it; never written to Beeper). */
    suspend fun setPin(chatId: String, pinned: Boolean): ActionResult = ActionResult.failure("Pinning unavailable")
    /** Contact nicknames by display name, when the phone may read contacts. */
    suspend fun nicknames(): Map<String, List<String>> = emptyMap()
    /** Chats matching a spoken name, best first. The default searches the loaded chats only. */
    suspend fun find(query: String): BeeperFind = BeeperPriority.find(query, chats, nicknames()).let { BeeperFind(it, BeeperPriority.confident(it)) }
}

data class BeeperFind(val matches: List<BeeperPriority.Match>, val confident: Boolean)

interface T3Repository {
    val threads: List<Thread>
    val settled: List<Thread>
    val usage: List<Usage>
    val environments: List<Environment>
    val projects: List<Project>
    val machines: List<Environment> get() = environments
    val offlineCount: Int get() = machines.count { !it.online }
    val needYouCount: Int get() = threads.count { it.status in T3_NEEDS_YOU }

    fun detail(machine: String, id: String): ThreadDetail?

    suspend fun refreshThreads(): ActionResult

    suspend fun refreshThread(machine: String, id: String): ActionResult

    suspend fun refreshSettled(): ActionResult

    suspend fun refreshUsage(): ActionResult

    suspend fun refreshEnvironments(): ActionResult

    suspend fun refreshProjects(): ActionResult

    suspend fun visit(machine: String, id: String): ActionResult

    suspend fun send(machine: String, id: String, text: String, mode: String = "send"): ActionResult

    suspend fun respond(
        machine: String,
        id: String,
        requestId: String,
        decision: String? = null,
        answers: Map<String, List<String>> = emptyMap(),
    ): ActionResult

    suspend fun newThread(machine: String, projectId: String, text: String): ActionResult

    suspend fun resetUsage(machine: String, instanceId: String, creditId: String): ActionResult

    /** Threads watched from the glasses ("machine:threadId"): only these notify (needs you = popup, finished = stream). */
    val watched: Set<String> get() = emptySet()

    fun isWatched(machine: String, id: String) = "$machine:$id" in watched

    /** Watch or stop watching a thread; stored on the gateway. */
    suspend fun watch(machine: String, id: String, title: String, on: Boolean): ActionResult = ActionResult.failure("Watching threads is not available")

    /** Gateway setting "Notify for all threads needing me" (needs-you pop-ups from unwatched threads too); default on. */
    val notifyAllNeeds: Boolean get() = true

    suspend fun refreshNotifySettings(): ActionResult = ActionResult()

    suspend fun setNotifyAllNeeds(on: Boolean): ActionResult = ActionResult.failure("Not available")
}

interface MediaRepository {
    val current: NowPlaying? get() = nowPlaying
    val nowPlaying: NowPlaying?
    val books: List<Book>
    val playlists: List<Playlist>
    val devices: List<Device>
    /** Names of the YouTube lists; the glasses show the first one (the gateway's YOUTUBE_LIST_NAME, default "Picks"). */
    val youtubeLists: List<String>

    fun videos(key: String): List<Video>

    suspend fun refresh(): ActionResult

    suspend fun refreshVideos(key: String): ActionResult

    suspend fun control(action: String, value: Double? = null): ActionResult

    suspend fun play(source: String, id: String): ActionResult

    suspend fun transfer(deviceId: String): ActionResult

    /** Other playlists in the user's Spotify library (live only; never demo items in live mode). */
    val libraryPlaylists: List<Playlist> get() = emptyList()

    /** Parts of the Spotify lists the gateway could not read (`playback`, `playlists`, `recent`). */
    val spotifyErrors: List<String> get() = emptyList()

    /** Progress while [play] starts an app on the phone (e.g. "Starting Spotify on phone…"); null when idle. */
    val startStatus: String? get() = null

    /** Start [uri] on a chosen device, after `play` answered [SPOTIFY_CHOOSE_DEVICE]. */
    suspend fun playOn(uri: String, deviceId: String): ActionResult = play("spotify", uri)
}

/** ActionResult.id when Spotify has no active device and the wearer must pick one. */
const val SPOTIFY_CHOOSE_DEVICE = "spotify:choose-device"

val T3_NEEDS_YOU = setOf("input", "approval", "request", "limited", "failed")
interface RemindersRepository {
    val armed: List<Reminder>
    suspend fun refresh(): ActionResult
    suspend fun cancel(id: String): ActionResult
}
private object EmptyReminders : RemindersRepository {
    override val armed = emptyList<Reminder>()
    override suspend fun refresh() = ActionResult()
    override suspend fun cancel(id: String) = ActionResult.failure("Reminders unavailable")
}

interface CalendarRepository {
    val agenda: List<CalendarDay>
    val dateLabel: String
        get() = ""

    suspend fun refresh(): ActionResult
}

interface PeopleRepository {
    val notice: String? get() = null
    val snapshot: PeopleSnapshot

    suspend fun refresh(): ActionResult

    suspend fun remind(personId: String, text: String): ActionResult
    suspend fun mute(personId: String, muted: Boolean): ActionResult = ActionResult.failure("Unavailable")
    suspend fun refreshPerson(personId: String): ActionResult = refresh()
}

interface HealthRepository {
    val metrics: List<Metric>
    val updatedLabel: String
        get() = ""

    suspend fun refresh(): ActionResult
}

interface HaRepository {
    val panel: HomeAssistantPanel

    suspend fun refresh(): ActionResult

    suspend fun scene(id: String): ActionResult

    suspend fun alarm(time: String? = null, style: String? = null): ActionResult
}

interface DocsRepository {
    val documents: List<Document>

    suspend fun refresh(): ActionResult

    suspend fun refreshDocument(id: String): ActionResult

    suspend fun delete(id: String): ActionResult
}

interface ReportRepository {
    val snapshot: ReportSnapshot

    suspend fun refresh(): ActionResult
}

interface NotificationsRepository {
    val items: List<Notification>

    suspend fun refresh(): ActionResult

    suspend fun dismiss(id: String): ActionResult

    suspend fun clear(): ActionResult

    /** Routing contract: listeners get every notification whose route is "popup" as it arrives. */
    fun listenPopups(listener: (Notification) -> Unit): () -> Unit = {}

    /** The target to open for [n] (Beeper rows without a chat id are resolved through the gateway); null = no app. */
    suspend fun resolveOpen(n: Notification): OpenTarget? = n.open
}

interface MemosRepository {
    val snapshot: MemoSnapshot
    val script: String

    suspend fun refresh(): ActionResult

    suspend fun toggle(): ActionResult
}

interface StatusRepository {
    val snapshot: StatusSnapshot

    suspend fun refresh(): ActionResult
}

interface SettingsRepository {
    val snapshot: SettingsSnapshot

    suspend fun refresh(): ActionResult

    suspend fun update(settings: SettingsSnapshot): ActionResult
}

/** Gateway defaults for the single YouTube list (YOUTUBE_LIST_KEY / YOUTUBE_LIST_NAME) before /youtube/lists has loaded. */
const val DEFAULT_YOUTUBE_LIST_KEY = "picks"
const val DEFAULT_YOUTUBE_LIST_NAME = "Picks"
