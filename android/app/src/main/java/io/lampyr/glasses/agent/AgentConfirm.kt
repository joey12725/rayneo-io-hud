package io.lampyr.glasses.agent

import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*

/**
 * Glasses confirm card for a sensitive agent action: Y confirms, B (or leaving the card) cancels,
 * and the registry treats no answer within the timeout as cancel.
 */
class AgentConfirm(
    ui: Ui,
    val title: String,
    val summary: String,
    private val seconds: Int,
    private val decide: (Boolean) -> Unit,
) : ListView(
    ui,
    "agentconfirm",
    items = { listOf(Row(title, sub = summary.takeIf { it.isNotBlank() }, icon = "joy")) },
    chrome = { t -> frame(ui, "${io.lampyr.glasses.settings.Names.assistant} wants to", "joy", t) },
    hint = "Y confirm   B cancel   ${seconds}s",
) {
    private var decided = false
    /** Set once the Ui has closed the card (B, B hold -> Home, replaced...): it is then off the stack or being removed. */
    private var closed = false

    init {
        textTitle = { "${io.lampyr.glasses.settings.Names.assistant} wants to" }
        textContext = { "${seconds}s" }
        textHint = { "Y confirm B cancel" }
    }

    private fun answer(value: Boolean) {
        if (decided) return
        decided = true
        decide(value)
    }

    override fun key(k: String): Boolean {
        when (k) {
            "sel" -> { answer(true); dismiss() }
            "back" -> { answer(false); dismiss() }
        }
        // Every other key is swallowed so a stray press cannot act on the screen underneath.
        return true
    }

    override fun legend() = mapOf("sel" to "Confirm: $title", "back" to "Cancel")

    override fun onClose() { closed = true; answer(false) }

    /**
     * Removes the card wherever it is on the stack (no-op when already gone). The answer can resume the waiting
     * call inline from inside a Ui stack operation (goHome iterates the stack calling onClose), so a card the Ui
     * already closed must never touch the stack again.
     */
    fun dismiss() {
        if (closed) return
        if (ui.stack.lastOrNull() === this) ui.pop()
        else if (ui.stack.remove(this)) { onClose(); ui.requestRender() }
    }
}
