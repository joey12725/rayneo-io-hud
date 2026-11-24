package io.lampyr.glasses.data

/**
 * Offline mirror of the gateway's notification policy (server/gateway/apis/glasses/lib/notify-policy.mjs).
 * Both are tested against server/gateway/test/fixtures/notify-policy.json; change one, change the other.
 * The tables (kinds, packages, the apps allow-list, deny list, dating preset) are data: [NotifyPolicy], taken from
 * [CompanionConfig.policy] (the default, or the user's/gateway's policy merged over it).
 *
 * Contract (POST /glasses/v1/notify/route): route = suppress (neither list nor popup), stream (Notifications list
 * only) or popup (list and a card that lights a dark lens). [NotificationRules.decide] returns null for a semantic
 * case that only the gateway's classifier decides; [NotificationRules.fallback] is the offline answer for those and
 * never pops up.
 */
data class NoticeInput(
    val kind: String = "app",
    val source: String = "",
    val app: String = "",
    val title: String = "",
    val body: String = "",
    val conversationTitle: String = "",
    val sender: String = "",
    val network: String = "",
    val isGroup: Boolean = false,
    val chatId: String = "",
    val channelId: String = "",
    val category: String = "",
    /** null = no contact information. */
    val contactKnown: Boolean? = null,
    val contactStarred: Boolean? = null,
    val contactName: String = "",
    val callerVerified: String = "none",
    /** Calls: answered calls from this number before this one (call log). */
    val priorAnsweredCalls: Int = 0,
    /** Calls: the owner has placed a call to this number before. */
    val ownerCalledNumber: Boolean = false,
    /** Calls: private / withheld / unknown presentation. */
    val privateNumber: Boolean = false,
    /** Calls: carrier or screening spam verdict. */
    val spam: Boolean = false,
    /** Calls: the platform marks a business caller (business caller ID). */
    val business: Boolean = false,
    /** Optional open target for internal kinds (calendar day, clock item, assistant session). */
    val target: String = "",
    val popup: Boolean = false,
)

/** The highest route a semantic case may get (see [NotificationRules.ceiling]). */
data class NoticeCap(val route: String, val reason: String)

data class NoticeDecision(
    val route: String,
    val importance: Double,
    val reason: String,
    val by: String = "rule",
    /** Contract open target: app id and optional app-specific target. */
    val app: String? = null,
    val target: String? = null,
)

object NotificationRules {
    private fun current() = io.lampyr.glasses.settings.CompanionConfig.current
    /** The active policy tables (phone Settings or the gateway's, merged over the default). */
    val policy: NotifyPolicy get() = current().policy
    val BEEPER get() = policy.beeperPackages.firstOrNull() ?: "com.beeper.android"
    val T3_PACKAGES: Set<String> get() = policy.t3Packages.toSet()
    val DIALERS: Set<String> get() = policy.dialerPackages.toSet()
    val DATING_PACKAGES: Set<String> get() = policy.activeDating.keys
    val DENY: Map<String, List<String>> get() = policy.deny
    private val APP_ROUTES = mapOf("popup" to .8, "stream" to .5, "suppress" to 0.0)

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)
    // Keep identical to RX in notify-policy.mjs.
    private val SHARE = rx("^(?:https?://)?(?:www\\.|m\\.|vm\\.|vt\\.)?(?:instagram\\.com/(?:reels?|p|tv|share|stories)/|tiktok\\.com/|youtube\\.com/shorts/|facebook\\.com/(?:reels?|share/[rv])/|fb\\.watch/)\\S*$|^(?: )?sent an? (?:reel|post|story|tiktok|short)\\b.*$|^(?: )?shared an? (?:reel|post|story|tiktok|short)\\b.*$")
    private val SHORT_VIDEO = rx("(?:https?://)?(?:www\\.|m\\.|vm\\.|vt\\.)?(?:instagram\\.com/(?:reels?|tv)/|tiktok\\.com/|youtube\\.com/shorts/|facebook\\.com/reels?/|fb\\.watch/)\\S*")
    private val INSTAGRAM_LINK = rx("(?:https?://)?(?:www\\.|m\\.)?(?:instagram\\.com|instagr\\.am)/\\S*")
    private val URL = rx("https?://\\S+|\\b(?:www\\.)?[a-z0-9-]+\\.(?:com|org|net|io|co|be|gg|tv|me|ly|app|dev)/\\S*")
    private val STORY_REACTION = rx("^(?:[\\uE000-\\uF8FF] )?(?:reacted(?: \\S{1,8})? to your story|liked your story|mentioned you in (?:their|a|his|her) story|reacted to (?:their|a) story)")
    private val STORY_REPLY = rx("^(?:[\\uE000-\\uF8FF] )?(?:replied to your story|story reply)\\b")
    private val ANNOUNCEMENTS = rx("(?:^|[#\\s|·:/(\\[-])(?:announcements?|announce|updates?|changelogs?|release[- ]?notes|releases|patch[- ]?notes|product[- ](?:news|updates?)|news(?:letter)?)(?:$|[\\s|·:/)\\]-])")
    private val MORNING_REPORT = rx("\\bmorning (?:report|brief(?:ing)?|digest|summary|update|roundup|news)\\b|\\bdaily (?:brief(?:ing)?|digest|report|summary|roundup)\\b|\\bgood morning[,!.]? (?:here'?s|this is) (?:your|today'?s|the)\\b")
    private val SPAM_CALLER = rx("\\b(?:spam|scam|fraud|telemarket\\w*|robocall)\\b")
    private val BUSINESS = rx("\\b(?:pizz(?:a|eria)|domino'?s|papa ?john'?s|little caesars?|restaurant|grill|kitchen|cafe|bistro|diner|deli|taqueria|tacos?|bbq|barbecue|sushi|burgers?|wings|pho|catering|bakery|doordash|door ?dash|uber ?eats|grubhub|postmates|instacart|deliver(?:y|ies)|courier|ups|fedex|usps|dhl|amazon|post office|apartments?|apts?|leasing|property|properties|management|mgmt|residences?|realty|lofts|pharmacy|cvs|walgreens|clinic|dental|dentist|orthodont\\w*|veterinar\\w*|animal hospital|tires?|collision|mechanic|plumbing|plumber|hvac|locksmith|towing|salon|barber(?:shop)?|cleaners|laundry|storage|movers|moving|pool|spas?|repair)\\b")
    private val NOT_BUSINESS = rx("\\b(?:survey|polls?|polling|research|opinion|call ?cent(?:er|re)|telemarket\\w*|marketing|warranty|insurance|political|campaign|vote|fundrais\\w*|donat\\w*|sweepstakes|prize|loans?|debt|credit|refinanc\\w*|solar|medicare|health ?plan|toll ?free|unknown|unavailable|private|restricted|spam|scam|fraud|robocall)\\b")
    private val MEDIA = rx("^(?:sent (?:a |an )?(?:photo|picture|image|video|gif|sticker|attachment|file|voice message)|photo|image|video|gif|sticker|voice message)(?: \\(\\d+:\\d\\d\\))?[.!]?$")
    private val REACTION = rx("^(?:reacted\\b|liked\\s+[\"“]|loved\\s+[\"“]|laughed at\\b|emphasized\\b|disliked\\b|questioned\\b)")
    private val BANTER = rx("^(?:lol|lmao|haha+|ha|😂+|👍|❤️|nice|same|\\+1|ok|k|yes|no|true|fr|bet)[!.\\s]*$")
    /** One-time, 2FA, verification and security codes; a code a friend mentions ("the door code is 4821") is not one. */
    private val OTP = rx("\\b\\d{4,8}\\b.*\\b(?:passcode|otp|verification|verify|one[- ]time|2fa)\\b|\\b(?:passcode|otp|verification|one[- ]time|2fa)\\b.*\\b\\d{4,8}\\b|\\b(?:verification|security|login|log[- ]in|sign[- ]?in|2fa|two[- ]factor|one[- ]time|authenticat(?:ion|or)|access|otp|pass) ?code\\b|\\byour (?:[\\w'-]+ ){0,3}code (?:is|:)|\\bdo(?: not|n'?t) share (?:this|the|your) code\\b|\\buse (?:code )?\\d{4,8} to (?:verify|sign in|log in|confirm|continue)\\b|\\bis your [\\w .'-]{0,30} code\\b|\\b[A-Z]-\\d{6}\\b")
    private val PROMO = rx("unsubscribe|newsletter|limited[- ]time offer|\\bpromo(?:tion)?\\b|\\bsale\\b|discount|\\b\\d+% off\\b|reply stop")
    private val DATING_MATCH = rx("\\bit'?s a match\\b|\\bnew match\\b|\\byou(?: have| got)? (?:a )?(?:new )?match\\b|\\bmatched with\\b|\\byou matched\\b|\\bmutual match\\b")
    private val DATING_MESSAGE = rx("\\bsent you a (?:new )?message\\b|\\bnew message\\b|\\bmessaged you\\b|\\breplied to you\\b|\\bwrote to you\\b|\\bsent you a (?:photo|voice|video)\\b")
    private val DATING_LIKE = rx("\\blikes? you\\b|\\bliked you\\b|\\bnew likes?\\b|\\bsomeone liked\\b|\\bsuper ?liked you\\b|\\bliked your (?:profile|photo|prompt)\\b|\\bpinged you\\b|\\bsent you a ping\\b")
    private val DATING_PROMO = rx("\\bboost\\b|\\bupgrade\\b|\\bpremium\\b|\\btinder (?:gold|platinum|plus)\\b|\\bsubscri|\\bdiscount|\\b\\d+% off\\b|\\boffer\\b|\\blimited time\\b|\\bdon'?t miss\\b|\\bcome back\\b|\\bwe miss you\\b|\\btop picks?\\b|\\brecommended\\b|\\bpicks? for you\\b|\\bpeople (?:near|around) you\\b|\\bnew people\\b|\\bcomplete your profile\\b|\\bprofile tips?\\b|\\badd (?:a |more )?photos?\\b|\\bupdate your profile\\b|\\bkeep swiping\\b|\\bstart swiping\\b|\\bdaily picks\\b|\\bstandouts\\b|\\bspotlight\\b|\\bit'?s been a while\\b|\\bcheck out who\\b|\\bsee who likes you\\b|\\bexpir(?:e|es|ing)\\b|\\blast chance\\b|\\bsurvey\\b|\\brecommendations?\\b|\\bnearby\\b|\\bnear you\\b|\\bin your area\\b|\\bstreaks?\\b|\\bmajestic\\b|\\bswipe surge\\b|\\bpassport\\b")
    /** Android Notification.category values a dating app only uses for marketing (CATEGORY_PROMO, CATEGORY_RECOMMENDATION). */
    private val DATING_SUPPRESS_CATEGORIES = setOf("promo", "recommendation")
    private val T3_NEEDS = rx("\\bneeds? (?:you|your)\\b|\\bapprov|\\bpermission\\b|\\bquestion\\b|\\bwaiting for (?:you|input|your)\\b|\\binput (?:required|needed)\\b|\\brequest(?:ed|s)? (?:input|approval)\\b")
    private val T3_DONE = rx("\\bfinished\\b|\\bcompleted?\\b|\\bdone\\b|\\bsucceeded\\b")
    /** A scheduled job's "nothing to deliver" answer (Hermes' sentinel): the whole reply, or its first or last line. */
    private val SILENT = rx("^\\s*\\[?(?:SILENT|NO[_ ]REPLY)\\]?\\s*$|^\\s*\\[SILENT\\]|\\[SILENT\\]\\s*$")
    private val T3_KEY = Regex("^[^:\\s]+:[^:\\s]+$")
    private val NEEDS_CATEGORIES = setOf("needs-you", "needs_you", "needs-input", "needs_input", "approval", "input", "request", "question", "choice", "user-input")
    private val DONE_CATEGORIES = setOf("done", "finished", "completed", "complete", "error", "failed", "limited")

    private fun rule(route: String, importance: Double, reason: String, app: String? = null, target: String? = null) =
        NoticeDecision(route, importance, reason, "rule", app, target?.takeIf { it.isNotBlank() })

    private fun beeper(pkg: String, pol: NotifyPolicy) = pol.beeperPackages.any { it.equals(pkg, true) }
    private fun t3(pkg: String, pol: NotifyPolicy) = pol.t3Packages.any { it.equals(pkg, true) }
    private fun dialer(pkg: String, pol: NotifyPolicy) = pol.dialerPackages.any { it.equals(pkg, true) }

    /** Old builds sent no kind: infer it from the package or label exactly like the gateway's inferKind. */
    fun inferKind(source: String, app: String, pol: NotifyPolicy = policy, assistant: String = current().assistantName): String {
        val s = source.lowercase(); val label = "$s ${app.lowercase()}"
        return when {
            beeper(s, pol) || Regex("\\bbeeper\\b").containsMatchIn(label) -> "beeper"
            t3(s, pol) || Regex("\\bt3(?:[ _-]*code)?\\b").containsMatchIn(label) -> "t3"
            // "joy" is the assistant screen's internal id, the source of the app's own assistant notifications.
            Regex("\\bhermes\\b|^assistant\\b|^joy\\b").containsMatchIn(s) || (assistant.isNotBlank() && s == assistant.lowercase()) -> "assistant"
            dialer(s, pol) -> "call"
            else -> "app"
        }
    }

    /**
     * The allow-list for phone notifications: only these packages ever leave the phone (everything else is suppressed
     * locally with no network call). Calls arrive through the call path, not here.
     */
    fun allowedPackage(pkg: String, extra: Set<String> = emptySet(), pol: NotifyPolicy = policy) =
        beeper(pkg, pol) || t3(pkg, pol) || pol.activeDating.keys.any { it.equals(pkg, true) } ||
            pol.apps.any { (app, route) -> route != "suppress" && app.equals(pkg, true) } || pkg in extra

    fun kindFor(pkg: String, pol: NotifyPolicy = policy) = when {
        beeper(pkg, pol) -> "beeper"
        t3(pkg, pol) -> "t3"
        dialer(pkg, pol) -> "call"
        else -> "app"
    }

    fun denyReason(source: String, pol: NotifyPolicy = policy): String = pol.deny.entries.firstOrNull { source in it.value }?.let { "deny.${it.key}" } ?: "deny.default"

    private fun text(n: NoticeInput) = "${n.title} ${n.body}".trim()

    private fun t3Stage(n: NoticeInput): String? {
        val c = n.category.lowercase()
        return when {
            c in NEEDS_CATEGORIES -> "needs"
            c in DONE_CATEGORIES -> "done"
            T3_NEEDS.containsMatchIn(text(n)) -> "needs"
            T3_DONE.containsMatchIn(text(n)) -> "done"
            else -> null
        }
    }

    /** A caller name that reads as a local business or service (surveys and call centres excluded). */
    fun businessCaller(name: String) = BUSINESS.containsMatchIn(name) && !NOT_BUSINESS.containsMatchIn(name)

    /**
     * The call policy, same order as callRules in notify-policy.mjs: private/withheld -> stream; a number the owner
     * has had real calls with -> popup whatever else is known; failed verification and spam -> suppress; saved
     * contacts and local businesses -> popup; other unknown numbers -> stream. A missed call that would pop up is stream.
     */
    private fun call(n: NoticeInput): NoticeDecision {
        val missed = n.category.lowercase() in setOf("missed", "missed_call", "missed-call")
        fun up(importance: Double, reason: String) = if (missed) rule("stream", .8, "call.missed", "calls", "recents") else rule("popup", importance, reason, "calls")
        return when {
            n.privateNumber -> rule("stream", .5, "call.private", "calls", "recents")
            n.priorAnsweredCalls > 0 || n.ownerCalledNumber -> up(.95, "call.history")
            n.callerVerified == "failed" -> rule("suppress", 0.0, "call.unverified")
            n.spam || (n.contactKnown != true && SPAM_CALLER.containsMatchIn(n.title)) -> rule("suppress", 0.0, "call.spam")
            n.contactKnown == true -> up(.95, "call.contact")
            n.business || businessCaller(n.title) -> up(.85, "call.business")
            else -> rule("stream", .5, "call.unknown", "calls", "recents")
        }
    }

    /** A short-video link with only a few words around it and no question: a share, not a message. */
    fun shortVideoShare(body: String): Boolean {
        if (!SHORT_VIDEO.containsMatchIn(body)) return false
        val rest = URL.replace(body, " ").replace(Regex("\\s+"), " ").trim()
        return rest.length <= 40 && !rest.contains('?')
    }

    /**
     * The highest route a semantic case may get, whatever the classifier says (mirror of ceiling() in notify-policy.mjs): morning
     * reports are at most stream; on Beeper, Instagram and short-video links, story replies and group chats that carry
     * an app's announcements or update posts never pop up. Null = no ceiling.
     */
    fun ceiling(n: NoticeInput): NoticeCap? {
        val body = n.body.trim(); val head = "${n.title} ${n.conversationTitle}"
        if (MORNING_REPORT.containsMatchIn("$head ${body.take(200)}")) return NoticeCap("stream", "cap.morning-report")
        if (n.kind != "beeper") return null
        return when {
            SHORT_VIDEO.containsMatchIn(body) || INSTAGRAM_LINK.containsMatchIn(body) -> NoticeCap("stream", "cap.instagram")
            STORY_REPLY.containsMatchIn(body) -> NoticeCap("stream", "cap.story")
            n.isGroup && ANNOUNCEMENTS.containsMatchIn(head) -> NoticeCap("stream", "cap.announcements")
            else -> null
        }
    }

    private fun dating(n: NoticeInput, table: DatingChannels): NoticeDecision? {
        val ch = n.channelId.lowercase()
        fun has(list: List<String>) = ch.isNotEmpty() && list.any { it.lowercase() == ch }
        val all = text(n)
        return when {
            has(table.deny) -> rule("suppress", .05, "dating.channel")
            has(table.message) -> rule("popup", .8, "dating.message")
            n.category.lowercase() in DATING_SUPPRESS_CATEGORIES -> rule("suppress", .05, "dating.category")
            (has(table.match) || has(table.like)) && DATING_PROMO.containsMatchIn(all) -> rule("suppress", .05, "dating.promo")
            has(table.match) -> rule("popup", .85, "dating.match")
            has(table.like) -> rule("stream", .55, "dating.like")
            DATING_PROMO.containsMatchIn(all) -> rule("suppress", .05, "dating.promo")
            DATING_MATCH.containsMatchIn(all) -> rule("popup", .85, "dating.match")
            DATING_MESSAGE.containsMatchIn(all) -> rule("popup", .8, "dating.message")
            DATING_LIKE.containsMatchIn(all) -> rule("stream", .55, "dating.like")
            else -> null
        }
    }

    /**
     * Deterministic rules, default deny. [watched] answers for a T3 thread key "machine:threadId"; [matchWatched]
     * finds a watched thread named in free text (the T3 Code app's notifications carry no thread id).
     * Returns null for a semantic case.
     */
    fun decide(n: NoticeInput, watched: (String) -> Boolean = { false }, matchWatched: (String) -> String? = { null },
               pol: NotifyPolicy = policy, owner: Regex? = current().ownerPattern): NoticeDecision? {
        val kind = when {
            n.kind == "app" && t3(n.source, pol) -> "t3"
            n.kind == "app" && beeper(n.source, pol) -> "beeper"
            n.kind == "joy" -> "assistant"
            else -> n.kind
        }
        if (kind != "app" && !pol.kindEnabled(kind)) return rule("suppress", 0.0, "kind.disabled")
        return when (kind) {
            // The assistant wrote it on purpose. The open target is the assistant screen (screen id "joy").
            "assistant" -> {
                val popup = n.popup || n.category.lowercase() == "popup"
                rule(if (popup) "popup" else "stream", if (popup) .9 else .7, if (popup) "assistant.popup" else "assistant.stream", "joy", n.target)
            }
            "call" -> call(n)
            "calendar" -> {
                val reminder = n.category.lowercase() in setOf("reminder", "starting", "alert", "event")
                rule(if (reminder) "popup" else "stream", if (reminder) .8 else .5, if (reminder) "calendar.reminder" else "calendar.update", "calendar", n.target)
            }
            "clock" -> {
                val ringing = n.category.lowercase() in setOf("alarm", "timer", "ringing")
                rule(if (ringing) "popup" else "stream", if (ringing) .95 else .5, if (ringing) "clock.ringing" else "clock.update", "clock", n.target)
            }
            // The assistant's Hermes exchanges (gateway POST /notify/hermes); the phone never builds these, the table stays in sync.
            "hermes" -> {
                val platform = n.source.lowercase().removePrefix("hermes:")
                val body = n.body.trim()
                when {
                    platform == "api_server" || platform == "glasses" -> rule("suppress", 0.0, "hermes.glasses", "joy")
                    body.isEmpty() -> rule("suppress", 0.0, "hermes.empty", "joy")
                    SILENT.containsMatchIn(body) -> rule("suppress", 0.0, "hermes.silent", "joy")
                    n.category.lowercase() == "local" -> rule("suppress", 0.0, "hermes.undelivered", "joy")
                    else -> null
                }
            }
            "t3" -> {
                val keyed = n.chatId.ifBlank { if (T3_KEY.matches(n.source)) n.source else "" }
                // The T3 Code app's own notifications: the gateway's T3 notifier owns T3 (events), so never twice.
                if (keyed.isBlank() && t3(n.source, pol)) return rule("suppress", 0.0, "t3.app")
                val key = keyed.ifBlank { matchWatched(text(n)).orEmpty() }
                if (key.isBlank() || !watched(key)) return rule("suppress", 0.0, "t3.unwatched")
                when (t3Stage(n)) {
                    "needs" -> rule("popup", .9, "t3.needs-you", "t3", key)
                    "done" -> rule("stream", .6, "t3.done", "t3", key)
                    else -> null
                }
            }
            "beeper" -> {
                val body = n.body.trim(); val all = text(n)
                when {
                    n.channelId.isNotEmpty() && n.channelId in pol.beeperNoticeChannels -> rule("suppress", 0.0, "beeper.notice")
                    body.isEmpty() && n.title.isEmpty() -> rule("suppress", 0.0, "beeper.empty")
                    SHARE.containsMatchIn(body) || shortVideoShare(body) -> rule("suppress", .05, "beeper.share", "beeper")
                    STORY_REACTION.containsMatchIn(body) -> rule("suppress", .05, "beeper.story", "beeper")
                    REACTION.containsMatchIn(body) -> rule("suppress", .05, "beeper.reaction", "beeper")
                    n.isGroup && BANTER.containsMatchIn(body) -> rule("suppress", .05, "beeper.banter", "beeper")
                    MEDIA.containsMatchIn(body) -> if (n.isGroup) rule("suppress", .05, "beeper.media", "beeper") else rule("stream", .4, "beeper.media", "beeper")
                    OTP.containsMatchIn(all) -> rule("suppress", .05, "beeper.otp", "beeper")
                    PROMO.containsMatchIn(all) && owner?.containsMatchIn(body) != true -> rule("suppress", .05, "beeper.promo", "beeper")
                    else -> null
                }
            }
            else -> {
                val source = n.source.lowercase()
                pol.activeDating.entries.firstOrNull { it.key.lowercase() == source }?.let { return dating(n, it.value) }
                if (dialer(source, pol)) return rule("suppress", 0.0, "call.dialer")
                pol.apps.entries.firstOrNull { it.key.lowercase() == source }?.value?.let { route -> return rule(route, APP_ROUTES[route] ?: 0.0, "app.$route") }
                rule("suppress", 0.0, denyReason(n.source, pol))
            }
        }
    }

    /** A semantic case with no gateway: never a popup. Beeper keeps the message in the list at low importance. */
    fun fallback(n: NoticeInput, pol: NotifyPolicy = policy): NoticeDecision {
        val kind = if (n.kind == "app" && t3(n.source, pol)) "t3" else if (n.kind == "app" && beeper(n.source, pol)) "beeper" else n.kind
        return when (kind) {
            "beeper" -> NoticeDecision("stream", .4, "model unavailable", "fallback", "beeper")
            // A watched thread that is neither "needs you" nor "finished": suppress.
            "t3" -> NoticeDecision("suppress", 0.0, "model unavailable", "fallback")
            // The assistant wrote it for the owner on purpose: keep it in the list rather than lose it.
            "hermes" -> NoticeDecision("stream", .5, "model unavailable", "fallback", "joy")
            else -> NoticeDecision("suppress", 0.0, "model unavailable", "fallback")
        }
    }

    /**
     * Text-free routing check of a real notification (debug bridge /notify/active): which rule decides it on the phone,
     * or that it goes to the gateway (its classifier) and what the offline answer would be. Same split as LiveDataStore.phoneNotification.
     * Never carries the title, body, sender or conversation: only package, channel, category and the decision.
     */
    fun check(n: NoticeInput, watched: (String) -> Boolean = { false }, matchWatched: (String) -> String? = { null }, pol: NotifyPolicy = policy): Map<String, Any> {
        val local = decide(n, watched, matchWatched, pol)
        val onPhone = local != null && (local.route == "suppress" || n.kind != "beeper")
        val offline = local ?: fallback(n, pol)
        return linkedMapOf(
            "pkg" to n.source, "kind" to n.kind, "channel" to n.channelId, "category" to n.category,
            "decidedBy" to if (onPhone) "phone-rule" else "gateway",
            "route" to (if (onPhone) local!!.route else "gateway"), "reason" to (local?.reason ?: "semantic"),
            "offlineRoute" to offline.route, "hasTitle" to n.title.isNotBlank(), "hasBody" to n.body.isNotBlank(),
        )
    }
}
