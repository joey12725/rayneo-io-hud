package io.lampyr.glasses.input

/** One disconnect edge across presence, device and input-path callbacks. Main-thread owned. */
class RemoteConnection(private val scheduler: Scheduler, private val disconnected: () -> Unit) : AutoCloseable {
    var connected = false
        private set
    private var reported = false
    private var settling: Scheduled? = null

    fun report(available: Boolean) {
        reported = available
        if (available || settling == null) transition(available)
    }

    fun switching() {
        settling?.cancel()
        reported = false
        settling = scheduler.after(3_000) {
            settling = null
            transition(reported)
        }
    }

    private fun transition(next: Boolean) {
        val lost = connected && !next
        connected = next
        if (lost) disconnected()
    }

    override fun close() { settling?.cancel(); settling = null }
}
