package io.lampyr.glasses.glasses

import android.graphics.Bitmap
import io.lampyr.glasses.render.TextGrid
import kotlinx.coroutines.flow.StateFlow

/** Input bits are the 540x220 master. Sent-only mirror frames may have empty bits and a fitted bitmap. */
/** [levels], when present, are the 0..15 brightness codes [bitmap] encodes (green = level * 17), so raster output need not re-derive them. */
data class Frame(val bits: ByteArray, val bitmap: Bitmap, val text: TextGrid, val levels: ByteArray? = null)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

interface GlassesTransport {
    val connectionState: StateFlow<ConnectionState>

    /** Implementations must avoid blocking the main dispatcher (move Bluetooth I/O to IO). */
    suspend fun show(frame: Frame)

    suspend fun connect() {}

    suspend fun disconnect() {}

    /** Display power: false = the lens must be dark (black frame or parked page); true = show frames again. */
    fun displayAwake(awake: Boolean) {}
    /** Called when the glasses report a wearer wake signal (look-up / crown) while dark. */
    var onWearerWake: ((String) -> Unit)?  get() = null; set(_) {}
}
