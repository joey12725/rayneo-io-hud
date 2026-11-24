package io.lampyr.glasses.debug

import org.json.JSONArray
import org.json.JSONObject

/**
 * Permanent button-to-display instrumentation (requirement 1.4).
 *
 * One sample per key press that changed the picture: the moment InputEngine acted, render start/end,
 * when the frame reached the raster worker, BEGIN transmitted, and the COMMIT acknowledgement. The
 * COMMIT ack is the last thing the phone can see; optical refresh happens after it.
 *
 * Stamps use one monotonic millisecond clock so they never mix time bases. Everything here is cheap,
 * thread safe and has no Android dependency, so plain JVM tests can drive it.
 */
object Latency {
    /** Why a frame was rendered. Raster pacing treats IDLE (animation only) frames as deferrable. */
    enum class Kind { INPUT, CHANGE, IDLE }
    data class Meta(val kind: Kind, val key: String?, val inputAt: Long?, val renderStart: Long, val renderEnd: Long, val offeredAt: Long = 0)
    data class Sample(val seq: Long, val key: String?, val inputAt: Long, val renderStart: Long, val renderEnd: Long,
                      val offeredAt: Long, val beginTx: Long, val commitRx: Long, val bytes: Int, val pipelined: Boolean,
                      val behindFrame: Boolean) {
        val renderMs get() = renderEnd - inputAt
        val queueMs get() = beginTx - renderEnd
        val wireMs get() = commitRx - beginTx
        val totalMs get() = commitRx - inputAt
    }
    const val WINDOW = 50
    const val STALE_INPUT_MS = 2_000L

    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    fun now() = clock()

    private var pendingKey: String? = null
    private var pendingAt: Long? = null
    /** True from a key action until the render that shows it starts; the render loop skips its throttle meanwhile. */
    @Volatile var inputPending = false; private set
    @Volatile var lastInputAt = Long.MIN_VALUE / 2; private set
    /** Weak, so the last few rendered frames (full bitmaps) are never kept alive just for measurement. */
    private val tagged = ArrayDeque<Pair<java.lang.ref.WeakReference<Any>, Meta>>()
    private val samples = ArrayDeque<Sample>()
    private var sequence = 0L

    /** InputEngine acted on [key]. The oldest unrendered action is the one measured. */
    @Synchronized fun input(key: String) {
        val at = now()
        if (pendingAt == null) { pendingAt = at; pendingKey = key }
        lastInputAt = at; inputPending = true
    }

    data class Ticket(val kind: Kind, val key: String?, val inputAt: Long?, val start: Long)
    /** Render loop, before Ui.render(). [dirty] separates real changes from animation-only renders. */
    @Synchronized fun renderStarted(dirty: Boolean): Ticket {
        val start = now()
        // A press that changed nothing leaves no render behind; never pin it on a much later, unrelated frame.
        val at = pendingAt?.takeIf { start - it <= STALE_INPUT_MS }
        val ticket = Ticket(if (at != null) Kind.INPUT else if (dirty) Kind.CHANGE else Kind.IDLE, if (at != null) pendingKey else null, at, start)
        pendingAt = null; pendingKey = null; inputPending = false
        return ticket
    }
    /** Render loop, after Ui.render(): remembers why [frame] exists, by identity, for the raster worker. */
    @Synchronized fun rendered(frame: Any, ticket: Ticket) {
        tagged.addLast(java.lang.ref.WeakReference(frame) to Meta(ticket.kind, ticket.key, ticket.inputAt, ticket.start, now()))
        while (tagged.size > 8) tagged.removeFirst()
    }
    @Synchronized fun metaFor(frame: Any): Meta? = tagged.lastOrNull { it.first.get() === frame }?.second

    /** Raster worker: a frame carrying a press was acknowledged by COMMIT. */
    @Synchronized fun committed(meta: Meta, beginTx: Long, commitRx: Long, bytes: Int, pipelined: Boolean, behindFrame: Boolean): Sample? {
        val inputAt = meta.inputAt ?: return null
        val sample = Sample(++sequence, meta.key, inputAt, meta.renderStart, meta.renderEnd, meta.offeredAt, beginTx, commitRx, bytes, pipelined, behindFrame)
        samples.addLast(sample)
        while (samples.size > WINDOW) samples.removeFirst()
        Trace.log("Latency", "key=${sample.key} total=${sample.totalMs} render=${sample.renderMs} queue=${sample.queueMs} " +
            "wire=${sample.wireMs} bytes=$bytes pipelined=$pipelined behindFrame=$behindFrame")
        return sample
    }
    @Synchronized fun recent(): List<Sample> = samples.toList()
    @Synchronized fun reset() { samples.clear(); tagged.clear(); pendingAt = null; pendingKey = null; inputPending = false }

    data class Stat(val median: Long, val p90: Long)
    fun stat(values: List<Long>): Stat? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        fun at(q: Double) = sorted[((sorted.size - 1) * q + 0.5).toInt().coerceIn(0, sorted.size - 1)]
        return Stat(at(0.5), at(0.9))
    }

    /** Debug bridge shape: {"n", "totalMs":{median,p90}, "renderMs", "queueMs", "wireMs", "pipelined", "samples":[...]} */
    fun summary(): JSONObject {
        val list = recent()
        fun put(o: JSONObject, name: String, values: List<Long>) {
            stat(values)?.let { o.put(name, JSONObject().put("median", it.median).put("p90", it.p90)) }
        }
        val o = JSONObject().put("n", list.size).put("window", WINDOW)
        put(o, "totalMs", list.map { it.totalMs }); put(o, "renderMs", list.map { it.renderMs })
        put(o, "queueMs", list.map { it.queueMs }); put(o, "wireMs", list.map { it.wireMs })
        o.put("pipelined", list.count { it.pipelined }).put("behindFrame", list.count { it.behindFrame })
        val array = JSONArray()
        list.forEach { s -> array.put(JSONObject().put("seq", s.seq).put("key", s.key).put("total", s.totalMs).put("render", s.renderMs)
            .put("queue", s.queueMs).put("wire", s.wireMs)
            .put("wait", s.renderStart - s.inputAt).put("draw", s.renderEnd - s.renderStart).put("offer", s.offeredAt - s.renderEnd).put("bytes", s.bytes).put("pipelined", s.pipelined).put("behindFrame", s.behindFrame)) }
        return o.put("samples", array)
    }
}
