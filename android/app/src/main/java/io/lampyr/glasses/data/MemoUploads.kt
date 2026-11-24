package io.lampyr.glasses.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** What the memo row shows for its upload state: "sent to <target>" once a delivery target has it. */
val Memo.uploadLabel: String get() = when (upload) {
    "uploading" -> "uploading"
    "uploaded" -> "uploaded"
    "delivered" -> deliveredTo.takeIf { it.isNotEmpty() }?.let { "sent to " + it.joinToString(", ") } ?: "uploaded"
    "failed" -> "failed"
    else -> "saved"
}
/** Finished upload states: nothing more to do for this memo. */
val Memo.uploadDone: Boolean get() = upload == "delivered" || upload == "failed"

object MemoAudio {
    /** Exact length of a PCM WAV written by [io.lampyr.glasses.audio.PcmAudio.wav]: data bytes / (rate * block). */
    fun seconds(wav: File): Double? = runCatching {
        RandomAccessFile(wav, "r").use { f ->
            if (f.length() < 44) return null
            val h = ByteArray(44).also { f.readFully(it) }
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            if (String(h, 0, 4) != "RIFF" || String(h, 8, 4) != "WAVE") return null
            val rate = b.getInt(24); val block = b.getShort(32).toInt()
            if (rate <= 0 || block <= 0) return null
            (f.length() - 44).toDouble() / (rate.toLong() * block)
        }
    }.getOrNull()
}

/** Sends one finished memo through the gateway's conversation route (kind "memo"); the gateway transcribes it and
 * delivers it to the targets routed for memos (a folder, a webhook, Hermes...).
 * Same stages and status handling as the ambient ConversationUploader: an idempotent POST of the
 * manifest (clientId = memo id), a PUT of the audio, then polling until every delivery has settled.
 * 400/413/415/422 are final; 404 restarts (at most three times); 429 and network errors retry later.
 * The audio is Ogg/Opus (the path ambient conversations use); if the phone cannot encode, the WAV is sent. */
class MemoUploader(private val gateway: GatewayClient, private val encode: (File, File) -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() }, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    suspend fun step(memo: Memo, save: suspend (Memo) -> Unit = {}): Memo {
        if (memo.uploadDone || memo.nextAttemptAt > now()) return memo
        var m = memo
        try {
            val wav = File(m.filePath)
            // File reads and the Opus encode run off the caller's thread (the data store's scope is the main thread).
            val seconds = withContext(Dispatchers.IO) { MemoAudio.seconds(wav) } ?: if (m.audioSent) m.durationSec.toDouble() else return m.failed("Audio file missing")
            if (seconds < 1.0) return m.failed("Too short")
            if (m.serverId == null) {
                val response = gateway.exchange("/ambient/conversations", "POST", manifest(m, seconds))
                when (response.code) {
                    in 200..299 -> {}
                    400, 413, 422 -> return m.failed("Gateway rejected memo (${response.code})")
                    429 -> return m.copy(nextAttemptAt = now() + 30 * 60_000, deliveryError = "Server busy; waiting 30 minutes")
                    else -> return m.retry("Gateway error (${response.code})")
                }
                val id = response.json().s("id"); require(id.isNotBlank())
                m = m.copy(serverId = id, upload = "uploading", deliveryError = null); save(m)
            }
            val path = "/ambient/conversations/${segment(m.serverId!!)}"
            if (!m.audioSent) {
                val audio = withContext(Dispatchers.IO) { prepare(m, wav) }
                if (audio.path != m.uploadPath) { m = m.copy(uploadPath = audio.path); save(m) }
                val response = gateway.putAudio("$path/audio", audio, if (audio.extension == "ogg") "audio/ogg" else "audio/wav")
                when (response.code) {
                    in 200..299 -> {}
                    404 -> return restart(m)
                    409 -> return m.failed("Server has different audio")
                    413, 415 -> return m.failed("Gateway rejected audio (${response.code})")
                    429 -> return m.copy(nextAttemptAt = now() + 30 * 60_000, deliveryError = "Server busy; waiting 30 minutes")
                    else -> return m.retry("Audio upload interrupted (${response.code})")
                }
                m = m.copy(audioSent = true, attempts = 0, deliveryError = null)
                return m.withServer(response.json())
            }
            val response = gateway.exchange(path)
            return when (response.code) {
                in 200..299 -> m.withServer(response.json())
                404 -> restart(m)
                else -> m.retry("Gateway error (${response.code})")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return m.retry("Upload waiting; retry scheduled") }
    }
    /** Applies the gateway's status view: `state` plus `deliveries[{target, name, state, deliveredAt?, error?}]`. */
    private fun Memo.withServer(view: JsonElement): Memo {
        val deliveries = ((view as? JsonObject)?.get("deliveries") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val done = deliveries.filter { it.s("state") == "delivered" }
        val names = done.map { it.s("name").ifBlank { it.s("target") } }.filter { it.isNotBlank() }.distinct()
        val lastAt = done.mapNotNull { it.s("deliveredAt").ifBlank { null } }.maxOrNull()
        val failures = deliveries.filter { it.s("state") == "failed" || (it.s("state") == "pending" && it.s("error").isNotBlank()) }
            .joinToString("; ") { "${it.s("name").ifBlank { it.s("target") }}: ${it.s("error", "failed")}" }.ifBlank { null }?.take(200)
        val withTargets = copy(delivered = names.isNotEmpty(), deliveredTo = names, deliveredAt = lastAt ?: deliveredAt)
        return when (view.s("state")) {
            "timed" -> withTargets.copy(upload = "delivered", attempts = 0, deliveryError = failures, nextAttemptAt = 0)
            "failed" -> withTargets.copy(upload = "failed", deliveryError = view.s("error").ifBlank { failures ?: "Server processing failed" }.take(200))
            "uploaded" -> withTargets.copy(upload = "uploaded", attempts = 0, deliveryError = failures, nextAttemptAt = now() + 60_000)
            else -> withTargets.copy(upload = "uploading", attempts = 0, nextAttemptAt = now() + 60_000)
        }
    }
    private fun Memo.failed(reason: String) = copy(upload = "failed", deliveryError = reason)
    private fun Memo.retry(reason: String) = copy(attempts = attempts + 1, deliveryError = reason,
        nextAttemptAt = now() + minOf(6 * 3600_000L, 5000L shl minOf(attempts, 12)))
    private fun restart(m: Memo) = if (m.restarts >= 3) m.failed("Server memo missing after three restarts")
        else m.copy(serverId = null, audioSent = false, upload = "", restarts = m.restarts + 1, nextAttemptAt = 0)
    /** Encode once and keep the file: a retried PUT must send the same bytes. */
    private fun prepare(m: Memo, wav: File): File {
        m.uploadPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }?.let { return it }
        require(wav.isFile) { "Audio file missing" }
        val ogg = File(wav.parentFile, wav.nameWithoutExtension + ".ogg")
        if (ogg.isFile && ogg.length() > 0) return ogg
        val tmp = File(wav.parentFile, wav.nameWithoutExtension + ".ogg.tmp").apply { delete() }
        val ok = runCatching { encode(wav, tmp) }.getOrDefault(false) && tmp.isFile && tmp.length() > 0
        if (ok && tmp.renameTo(ogg)) return ogg
        tmp.delete(); return wav
    }
    fun startedAt(m: Memo, seconds: Double): Instant = runCatching { Instant.parse(m.startedAt) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(m.createdAt).atZone(zone()).toInstant().minusMillis((seconds * 1000).toLong()) }.getOrNull()
        ?: Instant.ofEpochMilli(File(m.filePath).lastModified()).minusMillis((seconds * 1000).toLong())
    fun manifest(m: Memo, seconds: Double): JsonObject {
        val start = startedAt(m, seconds)
        val end = start.plusNanos((seconds * 1e9).toLong())
        val title = m.title.takeUnless { it.isBlank() || it == "Transcribing…" }?.take(120)
        return obj("clientId" to str(m.id), "device" to str("glasses"), "kind" to str("memo"), "title" to str(title),
            "startedAt" to str(start.toString()), "endedAt" to str(end.toString()), "speechSec" to JsonPrimitive(seconds),
            "tz" to str(zone().id), "segments" to JsonArray(listOf(obj("offsetSec" to JsonPrimitive(0), "at" to str(start.toString()),
                "durationSec" to JsonPrimitive(seconds)))), "fixes" to JsonArray(emptyList()))
    }
}

/** Copies only the upload fields from [from], so a concurrent title/transcript update is kept. */
fun Memo.withUpload(from: Memo) = copy(startedAt = from.startedAt.ifBlank { startedAt }, serverId = from.serverId, upload = from.upload,
    deliveryError = from.deliveryError, audioSent = from.audioSent, uploadPath = from.uploadPath, attempts = from.attempts,
    nextAttemptAt = from.nextAttemptAt, restarts = from.restarts, delivered = from.delivered, deliveredAt = from.deliveredAt,
    deliveredTo = from.deliveredTo)
