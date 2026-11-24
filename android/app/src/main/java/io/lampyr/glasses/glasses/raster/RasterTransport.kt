package io.lampyr.glasses.glasses.raster

import io.lampyr.glasses.render.RasterFit

enum class DisplayMode { AUTO, TEXT, RASTER }
enum class RasterKind { TVP2, GLASSLY }
@kotlinx.serialization.Serializable
data class RasterCapabilities(val turbo: Boolean = false, val glassly: GlasslyIdentity? = null) {
    fun select(mode: DisplayMode): RasterKind? = if (mode == DisplayMode.TEXT) null else when {
        turbo -> RasterKind.TVP2
        glassly?.raster == true -> RasterKind.GLASSLY
        else -> null
    }
}

/** Raw business payloads, not stock JSON envelopes. I/O is supplied by the authenticated link. */
interface RasterWire {
    fun reserveSequences(count: Int): Int
    suspend fun send(business: Int, payload: ByteArray, ble: Boolean = false, sequence: Int? = null): Int
    suspend fun awaitReply(business: Int, timeoutMs: Long = 3_000, matches: (ByteArray) -> Boolean): ByteArray
    /** Like [awaitReply], but null after [timeoutMs] instead of throwing; a real wire never drops a reply on that timeout. */
    suspend fun awaitReplyOrNull(business: Int, timeoutMs: Long, matches: (ByteArray) -> Boolean): ByteArray? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { awaitReply(business, Long.MAX_VALUE / 4, matches) }
}
interface RasterTransport {
    val kind: RasterKind
    suspend fun open()
    /** Return the quantized pixels actually committed (wire precision can differ from the requested levels). */
    suspend fun show(image: RasterFit.Image): RasterFit.Image
    suspend fun keepAlive()
    suspend fun close()
}
/** Firmware deferred an otherwise valid frame; keep the session and replace it with the newest one. */
class RasterFrameBusy : IllegalStateException("Raster frame deferred by firmware")
/** The firmware no longer knows our session (result 5 SESSION, e.g. it closed the page itself): reopen fast, do not fall back. */
class RasterSessionLost(message: String) : IllegalStateException(message)
/** A pipelined frame was refused or timed out; the caller reopens and resends stop-and-wait (raster.pipeline fallback). */
class RasterPipelineRefused(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
/** Timing of one show(): BEGIN transmitted, COMMIT acknowledged (elapsed ms), packed bytes, and whether pixels changed at all. */
data class RasterFrameTiming(val beginTx: Long, val commitRx: Long, val bytes: Int, val pipelined: Boolean, val sent: Boolean)

internal fun ByteArray.u8(i: Int) = this[i].toInt() and 255
internal fun ByteArray.le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.le32(i: Int): Long = (0..3).fold(0L) { a, n -> a or (u8(i + n).toLong() shl (8 * n)) }
internal fun ByteArray.put16(i: Int, n: Int) { this[i] = n.toByte(); this[i + 1] = (n ushr 8).toByte() }
internal fun ByteArray.put32(i: Int, n: Long) { repeat(4) { this[i + it] = (n ushr (it * 8)).toByte() } }
