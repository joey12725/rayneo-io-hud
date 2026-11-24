package io.lampyr.glasses.ui

/**
 * AMB-14: one switch for everything the glasses hear without being asked: the conversation recording
 * and voice matching (voiceprints for People). Dictation, memos and calls are not affected.
 * On by default. Hold − on the remote flips it; the phone's ambient controls flip the same state.
 */
interface PassiveListening {
    val on: Boolean
    fun set(on: Boolean)
    /** Returns an unsubscribe function. Called on the main thread when [on] changes elsewhere. */
    fun listen(listener: () -> Unit): () -> Unit
}

/** Used by tests and before the service attaches the persisted ambient preferences. */
class MemoryPassiveListening(initial: Boolean = true) : PassiveListening {
    private val listeners = mutableListOf<() -> Unit>()
    override var on = initial
        private set

    override fun set(on: Boolean) {
        if (this.on == on) return
        this.on = on
        listeners.toList().forEach { it() }
    }

    override fun listen(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
}
