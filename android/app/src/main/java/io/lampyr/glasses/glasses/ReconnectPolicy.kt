package io.lampyr.glasses.glasses

import kotlinx.coroutines.CompletableDeferred

/**
 * One connection attempt as the link supervisor hands it to the connector.
 * [background]: the fast budget is spent, so the connector uses its cheap mode (passive connect, short scans).
 * [urgent]: an event (glasses link, screen on, controller key) says the glasses may be awake right now.
 * [mayBond]: the connector may start Android's pairing dialog. Never true in background retries.
 */
data class LinkAttempt(val number: Int, val reason: String, val background: Boolean = false,
                       val urgent: Boolean = false, val mayBond: Boolean = true) {
    /**
     * Completed with a wake's reason while a cheap (passive) background attempt is still waiting: the connector then
     * switches to an active connect at once instead of waiting out its long passive window.
     */
    val hurry: CompletableDeferred<String> = CompletableDeferred()
    /** A background attempt that is not already urgent, so a wake should hurry it. */
    val passive get() = background && !urgent
}

enum class RetryPhase { IDLE, ATTEMPTING, WAITING, CONNECTED, STOPPED }

/** What the reconnect supervisor is doing, for honest phone and trace status. */
data class RetryStatus(val phase: RetryPhase = RetryPhase.IDLE, val attempt: Int = 0, val reason: String = "",
                       val background: Boolean = false, val nextAttemptAt: Long? = null, val error: String? = null) {
    /** Still trying by itself: either mid-attempt or waiting for the next one. */
    val searching get() = phase == RetryPhase.ATTEMPTING || phase == RetryPhase.WAITING
}

/** The glasses reconnect policy: a fast budget, then retries forever with a capped backoff; see docs/rayneo-protocol.md §9. */
object ReconnectPolicy {
    /** Attempts before the link drops to background (cheap) retries. The protocol's own supervisor stops here. */
    const val FAST_ATTEMPTS = 3
    const val MAX_DELAY_MS = 60_000L
    private val delays = longArrayOf(5_000, 10_000, 15_000, 20_000, 30_000, 45_000)

    /** Delay after failure number [failure] (0-based): 5, 10, 15 s (fast), then 20, 30, 45 and 60 s forever. */
    fun delayMs(failure: Int): Long = delays.getOrElse(failure.coerceAtLeast(0)) { MAX_DELAY_MS }

    const val UNPAIRED = "Glasses are no longer paired with this phone. Pair them again in Glasses setup"
    const val PERMISSION = "Bluetooth permission missing: allow Nearby devices for this app"

    /** Failures that retrying cannot fix: auth refused, account binding, lost pairing, missing permission. */
    fun isTerminal(message: String) = Regex(
        "authentication (proof )?rejected|auth(entication)? rejected|account-bound|bound to another|unbind (in|required)|" +
            "no longer paired|pairing declined|permission missing", RegexOption.IGNORE_CASE).containsMatchIn(message)

    /** The link's default clock ([RetryStatus.nextAttemptAt] is on it). */
    fun linkNow(): Long = System.nanoTime() / 1_000_000

    /** Honest one-line phone detail while the link retries by itself, or why it stopped. */
    fun detail(status: RetryStatus, now: Long = linkNow()): String = when (status.phase) {
        RetryPhase.ATTEMPTING -> if (status.background) "Searching for glasses in the background. Put them on or press a controller key"
            else "Looking for your glasses…"
        RetryPhase.WAITING -> {
            val seconds = ((status.nextAttemptAt ?: now) - now).coerceAtLeast(0).let { (it + 999) / 1000 }
            "Searching for glasses. Next try in $seconds s" + (status.error?.let { ". Last: ${it.take(70)}" } ?: "")
        }
        RetryPhase.STOPPED -> status.error ?: "Stopped. Press Connect to retry"
        else -> ""
    }
    /** For the phone notification: no countdown, so the text only changes with the phase. */
    fun notificationLine(status: RetryStatus): String = when (status.phase) {
        RetryPhase.ATTEMPTING, RetryPhase.WAITING -> "Glasses searching"
        RetryPhase.STOPPED -> "Glasses stopped, open the app"
        else -> ""
    }

    /** The user-facing message for a failure; a SecurityException is always a missing Bluetooth permission. */
    fun message(error: Throwable): String = when (error) {
        is SecurityException -> PERMISSION
        else -> error.message ?: error.javaClass.simpleName
    }
}

/**
 * How one attempt reaches the glasses. A bonded dual-mode unit (public address, as RayNeo iO pairs) needs no scan: a
 * sleeping or already-connected unit may not advertise to a scan at all, and Android's passive (autoConnect) GATT
 * connect waits for it in the controller's accept list, which costs almost no battery.
 */
data class ConnectPlan(val direct: Boolean, val autoConnect: Boolean, val windowMs: Long, val scanMs: Long) {
    companion object {
        const val ACTIVE_WINDOW_MS = 15_000L
        const val PASSIVE_WINDOW_MS = 180_000L
        const val FAST_SCAN_MS = 12_000L
        const val SHORT_SCAN_MS = 4_000L
        /**
         * [bondedLe]: the target address is bonded and LE-capable, so a direct connect needs no advert from a scan.
         * [heldElsewhere]: another app already holds an LE link to the glasses; connect straight onto it.
         */
        fun choose(attempt: LinkAttempt, bondedLe: Boolean, heldElsewhere: Boolean): ConnectPlan = when {
            heldElsewhere -> ConnectPlan(true, false, ACTIVE_WINDOW_MS, 0)
            bondedLe -> if (attempt.background && !attempt.urgent) ConnectPlan(true, true, PASSIVE_WINDOW_MS, 0)
                else ConnectPlan(true, false, ACTIVE_WINDOW_MS, 0)
            else -> ConnectPlan(false, false, ACTIVE_WINDOW_MS, if (attempt.background && !attempt.urgent) SHORT_SCAN_MS else FAST_SCAN_MS)
        }
    }
}
