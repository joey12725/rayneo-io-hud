package io.lampyr.glasses.audio

/**
 * A microphone that delivers 16-bit little-endian mono PCM at [sampleRate] Hz in small chunks.
 *
 * Implementations:
 * - glasses: RayNeo iO wearer (bone-conduction) channel, decoded from the glasses' Opus uplink
 *   and resampled (registered by the RayNeo transport in [MicRegistry.glasses] while connected);
 * - phone: Android AudioRecord fallback (also picks up a Bluetooth headset mic via the system route).
 *
 * Dictation always prefers the glasses (VOX-01) and falls back to the phone when the glasses
 * are not connected.
 */
interface MicSource {
    val name: String
    val sampleRate: Int get() = 16_000

    fun isAvailable(): Boolean

    /** Starts capture. [onPcm] is called from a background thread. Returns false if capture could not start. */
    fun start(onPcm: (ShortArray) -> Unit): Boolean

    fun stop()
}

object MicRegistry {
    /** Set by the glasses transport while connected and able to stream the wearer channel; null otherwise. */
    @Volatile var glasses: MicSource? = null
}
