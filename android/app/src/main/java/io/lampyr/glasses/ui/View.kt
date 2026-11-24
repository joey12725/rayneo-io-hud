package io.lampyr.glasses.ui

import io.lampyr.glasses.data.ActionResult
import io.lampyr.glasses.render.*

interface View {
    val id: String
    val kind: String

    fun draw(g: Gfx, t: Double)

    /** Only content-dense dashboards without a spare row may opt out. */
    val textHintRequired: Boolean get() = true

    fun drawText(tg: TextGrid)

    /** Text-entry views may reserve the otherwise free gestures. */
    val consumesShortcuts: Boolean get() = false

    fun key(k: String): Boolean = false

    fun legend(): Map<String, String> = emptyMap()

    val talk: TalkTarget?
        get() = null

    fun talkTap(): Boolean = false

    val needsAnimation: Boolean
        get() = false

    fun onOpen() {}

    fun onClose() {}

    /** True while this screen must keep the lens lit (live dictation, calls, teleprompter, the assistant call). */
    val keepAwake: Boolean
        get() = false
    /** Agent control (glasses.read_screen): the screen's full text. Null = the rendered text view is used. */
    fun agentText(): String? = null

    /**
     * Buttons this screen takes over from their global meaning while it is on top (requirement 15.3). The input
     * engine routes a claimed press here before any global action or assigned shortcut. Back can never be claimed.
     */
    fun overrides(): List<ButtonOverride> = emptyList()
}

/**
 * What a screen claims of one remote button. Only buttons with a global meaning that a screen may want can be
 * claimed: B (back, hold Home), L (talk), R2 (media), − (lock), Y, X, + and the arrows never; Y/X/+/arrows already
 * reach the screen through [View.key]. [tap]/[hold] say which half is claimed; an unclaimed hold keeps a global
 * hold shortcut working on that screen. [momentary] claims both and acts on key-down/key-up with no hold delay.
 */
data class ButtonClaim(
    val letter: String,
    val label: String,
    val tap: Boolean = true,
    val hold: Boolean = true,
    val momentary: Boolean = false,
) {
    init {
        require(letter in OVERRIDABLE) { "The $letter button cannot be overridden" }
        require(tap || hold || momentary) { "A claim must take the tap or the hold" }
    }

    /** True when this claim takes the [hold] (or tap) half of its button. */
    fun claims(hold: Boolean) = momentary || if (hold) this.hold else tap

    companion object {
        /** Buttons a screen may claim. B is deliberately absent: back and hold-for-Home always work. */
        val OVERRIDABLE = setOf("a", "r", "star", "heart", "l2")
    }
}

/** A screen's handlers for one [ButtonClaim]. Momentary claims use onDown/onUp/onCancel; the others onTap/onHold. */
class ButtonOverride(
    val claim: ButtonClaim,
    val onTap: () -> Unit = {},
    /** Runs when the claimed button is held 450 ms. Null: the hold has no screen action (the tap fires on release). */
    val onHold: (() -> Unit)? = null,
    val onDown: () -> Unit = {},
    val onUp: () -> Unit = {},
    /** A lost release, lock or phone call ends a momentary press without its key-up. */
    val onCancel: () -> Unit = {},
) {
    val letter get() = claim.letter
}

data class TalkTarget(
    val label: String,
    val target: String = "beeper",
    val direct: Boolean = false,
    val send: suspend (String) -> ActionResult,
    val steer: (suspend (String) -> ActionResult)? = null,
    /** Sent with the dictation so the gateway can build cleanup context (Beeper chatId; T3 machine/thread/project). */
    val context: Map<String, String> = emptyMap(),
    /** Send with the structured mentions from dictation; falls back to [send] when null. */
    val sendRich: (suspend (String, List<io.lampyr.glasses.data.Mention>) -> ActionResult)? = null,
    /** Act on the transcript as soon as it arrives (no Y on a preview): Phone's "say a name" search. */
    val instant: Boolean = false,
    /** Act on the transcript as soon as it arrives (no Y): only for lookups such as the Beeper find, never for sends. */
    val autoSend: Boolean = false,
)

abstract class Screen(val ui: Ui, override val id: String, override val kind: String = id) : View {
    val t0 = ui.nowS()
    override val needsAnimation
        get() = true

    abstract override fun drawText(tg: TextGrid)
}
