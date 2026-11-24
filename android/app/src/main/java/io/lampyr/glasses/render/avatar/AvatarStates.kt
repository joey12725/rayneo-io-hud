package io.lampyr.glasses.render.avatar

/**
 * Canonical avatar state names. Packs key their clips by these strings (see docs/avatar-packs.md).
 *
 * [ALL] lists every state in a stable order; the index + 1 is the legacy numeric id older clients sent
 * (for example an `emote` row with `"anim": 17` means `happy`). Only [IDLE] is required in a pack; anything else
 * resolves through the pack's `fallbacks`, then [BUILTIN_FALLBACKS], then `idle`.
 */
object AvatarStates {
    const val IDLE = "idle"
    const val SLEEPING = "sleeping"
    const val WAKING = "waking"
    const val LISTENING = "listening"
    const val THINKING = "thinking"
    const val WORKING = "working"
    const val SEARCHING = "searching"
    const val REMEMBERING = "remembering"
    const val SAVING = "saving"
    const val SPEAKING = "speaking"
    const val WAITING = "waiting"
    const val DELEGATING = "delegating"
    const val COMPRESSING = "compressing"
    const val ASKING = "asking"
    const val INTERRUPTED = "interrupted"
    const val ERROR = "error"
    const val HAPPY = "happy"
    const val CURIOUS = "curious"
    const val CELEBRATING = "celebrating"
    const val NERDY = "nerdy"
    const val CREATIVE = "creative"
    const val LOOKING_AROUND = "looking_around"
    const val WAVING = "waving"
    const val NODDING = "nodding"
    const val CHECKING_WATCH = "checking_watch"

    /** All canonical states; position + 1 is the legacy numeric id. */
    val ALL: List<String> = listOf(
        IDLE, SLEEPING, WAKING, LISTENING, THINKING, WORKING, SEARCHING, REMEMBERING, SAVING, SPEAKING,
        WAITING, DELEGATING, COMPRESSING, ASKING, INTERRUPTED, ERROR, HAPPY, "laughing", "smirking", "playful",
        "heart_eyes", "blushing", "sad", "concerned", "skeptical", CURIOUS, "surprised", "facepalm", CELEBRATING, "proud",
        "confused", "romantic", "spicy", NERDY, CREATIVE, "philosophical", "serious", "comforting", "teaching", "debating",
        "secretive", LOOKING_AROUND, "stretching", WAVING, NODDING, "shaking_head", "winking", "dancing", "coffee", CHECKING_WATCH,
    )

    /** Assistant statuses that pick their own avatar state (anything else shows [IDLE]). */
    val STATUS: Set<String> = setOf(
        IDLE, SLEEPING, WAKING, LISTENING, THINKING, WORKING, SEARCHING, REMEMBERING, SAVING, SPEAKING,
        WAITING, DELEGATING, ERROR, HAPPY, "laughing", CURIOUS, WAVING, NODDING, "coffee", CELEBRATING,
    )

    /** The avatar state for an assistant status string (`thinking`, `searching`, ...). */
    fun forStatus(status: String?): String = if (status != null && status in STATUS) status else IDLE

    /** Legacy numeric id (1..50) to state name, or null. */
    fun fromLegacyId(id: Int): String? = ALL.getOrNull(id - 1)

    /**
     * Built-in chain used after a pack's own `fallbacks`, so a pack that ships only the core states
     * (idle, listening, thinking, speaking, working, error, happy) still shows something fitting everywhere.
     */
    val BUILTIN_FALLBACKS: Map<String, String> = mapOf(
        SLEEPING to IDLE, WAKING to IDLE, LISTENING to IDLE, THINKING to IDLE, SPEAKING to IDLE,
        WORKING to THINKING, SEARCHING to WORKING, REMEMBERING to THINKING, SAVING to WORKING, WAITING to THINKING,
        DELEGATING to WORKING, COMPRESSING to WORKING, ASKING to THINKING, INTERRUPTED to LISTENING, ERROR to IDLE,
        HAPPY to IDLE, "laughing" to HAPPY, "smirking" to HAPPY, "playful" to HAPPY, "heart_eyes" to HAPPY,
        "blushing" to HAPPY, "sad" to ERROR, "concerned" to ERROR, "skeptical" to THINKING, CURIOUS to THINKING,
        "surprised" to IDLE, "facepalm" to ERROR, CELEBRATING to HAPPY, "proud" to HAPPY, "confused" to THINKING,
        "romantic" to HAPPY, "spicy" to HAPPY, NERDY to WORKING, CREATIVE to WORKING, "philosophical" to THINKING,
        "serious" to IDLE, "comforting" to HAPPY, "teaching" to SPEAKING, "debating" to SPEAKING, "secretive" to IDLE,
        LOOKING_AROUND to IDLE, "stretching" to IDLE, WAVING to HAPPY, NODDING to HAPPY, "shaking_head" to IDLE,
        "winking" to HAPPY, "dancing" to CELEBRATING, "coffee" to IDLE, CHECKING_WATCH to WAITING,
    )
}
