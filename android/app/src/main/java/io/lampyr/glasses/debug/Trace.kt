package io.lampyr.glasses.debug

import android.os.SystemClock
import android.util.Log
import io.lampyr.glasses.glasses.protocol.*
import kotlinx.serialization.json.*

/** Process-local flight recorder. Android calls are guarded for ordinary JVM transport tests. */
object Trace {
    data class Entry(val seq: Long, val t: Long, val up: Long, val cat: String, val msg: String, val isError: Boolean = false)
    @Volatile var verbose = false
    @Volatile var sink: ((Entry) -> Unit)? = null
    private val entries = ArrayDeque<Entry>()
    @Volatile private var secrets = emptyList<String>()
    /** Register gateway credentials at their settings boundary; they never enter the ring or hex. */
    @Synchronized fun protect(secret: String) {
        if (secret.isNotBlank() && secret !in secrets) secrets = (secrets + secret).takeLast(16)
    }
    fun safeText(text: String): String = secrets.fold(text) { value, secret -> value.replace(secret, "[redacted]") }
    private var sequence = 0L
    val latest: Long get() = synchronized(this) { sequence }
    fun log(cat: String, msg: () -> String) = log(cat, msg())
    fun log(cat: String, msg: String) = append(cat, msg, false)
    fun error(cat: String, msg: String, throwable: Throwable? = null) =
        append(cat, msg + (throwable?.let { "\n${it.stackTraceToString()}" } ?: ""), true)
    @Synchronized private fun append(cat: String, msg: String, error: Boolean) {
        val safe = safeText(msg)
        val entry = Entry(++sequence, System.currentTimeMillis(),
            runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000 }, cat, safe, error)
        entries.addLast(entry)
        if (entries.size > 5000) entries.removeFirst()
        runCatching { if (error) Log.e("Glasses/$cat", safe) else Log.i("Glasses/$cat", safe) }
        // Sink failure must never break a transport or recurse into this recorder.
        runCatching { sink?.invoke(entry) }
    }
    @Synchronized fun since(seq: Long, max: Int = 500): List<Entry> =
        entries.asSequence().filter { it.seq > seq }.take(max.coerceIn(0, 5000)).toList()
    fun content(text: String): String = if (verbose) safeText(text) else "[content hidden]"
    fun hex(bytes: ByteArray): String {
        if (!verbose) return ""
        // Latin-1 preserves arbitrary bytes while matching the exact UTF-8 credential bytes.
        val safe = secrets.fold(bytes.toString(Charsets.ISO_8859_1)) { value, secret ->
            value.replace(secret.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1), "[redacted]")
        }.toByteArray(Charsets.ISO_8859_1)
        return safe.take(96).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun credentialKey(key: String) = Regex("token|secret|credential|password|authorization|api.?key|proof", RegexOption.IGNORE_CASE).containsMatchIn(key)
    fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (k, v) -> if (credentialKey(k)) JsonPrimitive("[redacted]") else redact(v) })
        is JsonArray -> JsonArray(value.map(::redact))
        else -> value
    }

    private val streams = StreamCounter()
    /** One boundary per channel. Never dump pairing inputs or audio, including cached LifeLog. */
    fun frame(direction: String, channel: String, frame: RayneoFrame) {
        if (frame.business == 0x10) return
        val env = runCatching { Envelope.decode(frame.payload) }.getOrNull()
        val body = env?.let { runCatching { Json.parseToJsonElement(it.json) }.getOrNull() }
        val audio = env?.audio?.isNotEmpty() == true || (frame.business in setOf(0x13, 0x17, 0x14) && env?.type == 4) ||
            (frame.business == 0x0d && env?.type in setOf(163, 164))
        val sid = (body as? JsonObject)?.string("sid")
        val label = "$direction $channel business=${frame.business} type=${env?.type} seq=${frame.sequence} sid=$sid len=${frame.payload.size}"
        if (audio) {
            streams.add("$direction/$channel/${frame.business}/$sid", frame.payload.size)?.let { log("Audio", "$label $it") }
            return
        }
        // Preserve the actual wire bytes except when credential fields require redaction.
        val malformedAudio = env == null && frame.business in setOf(0x13, 0x17, 0x14, 0x0d)
        val payload = if (verbose && !malformedAudio) {
            val redacted = body?.let(::redact)
            val safe = if (env != null && body != null && redacted != body)
                env.copy(json = redacted.toString(), audio = null).encode() else frame.payload
            " hex=${hex(safe)}"
        } else ""
        log("Frame", label + payload)
    }
}

/** Counts traffic between emitted lines, with at most one line per second per stream. */
class StreamCounter(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Counts(var at: Long, var packets: Long = 0, var bytes: Long = 0, var drops: Long = 0)
    private val streams = linkedMapOf<String, Counts>()
    @Synchronized fun add(stream: String, bytes: Int, dropped: Boolean = false): String? {
        val now = clock()
        val count = streams.getOrPut(stream) { Counts(now) }
        count.packets++; count.bytes += bytes; if (dropped) count.drops++
        if (streams.size > 128) streams.remove(streams.keys.first())
        if (now - count.at < 1000) return null
        val line = "stream=$stream packets=${count.packets} bytes=${count.bytes} drops=${count.drops}"
        count.at = now; count.packets = 0; count.bytes = 0; count.drops = 0
        return line
    }
}
