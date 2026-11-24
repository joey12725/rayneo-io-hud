// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
// TOC timing adapted from OpenRayneo PromptAudio.kt. Downsampler is original RayNeo iO HUD code.
package io.lampyr.glasses.glasses

object OpusTiming {
    fun durationUs(packet: ByteArray): Long {
        require(packet.isNotEmpty())
        val toc = packet[0].toInt() and 255
        val frame = when {
            toc and 128 != 0 -> 2500L shl ((toc ushr 3) and 3)
            toc and 96 == 96 -> if (toc and 8 != 0) 20000L else 10000L
            else -> (10000L shl ((toc ushr 3) and 3)).coerceAtMost(60000)
        }
        val count = when (toc and 3) { 0 -> 1; 3 -> { require(packet.size >= 2); packet[1].toInt() and 63 }; else -> 2 }
        return (frame * count).also { require(it in 2500..120000) }
    }
}

/** Streaming 3-tap box low-pass + decimate by 3, selecting channel zero before filtering.
 * Modest stop-band attenuation; sufficient first-pass dictation, not archival audio fidelity.
 * Carries partial sample groups across codec output buffers.
 */
class WearerDownsampler {
    private var count = 0
    private var sum = 0
    fun reset() { count = 0; sum = 0 }
    fun process(interleaved: ShortArray, channels: Int): ShortArray {
        require(channels in 1..2 && interleaved.size % channels == 0)
        val result = ShortArray((interleaved.size / channels + count) / 3)
        var cursor = 0
        for (i in interleaved.indices step channels) {
            sum += interleaved[i]
            if (++count == 3) { result[cursor++] = (sum / 3).toShort(); sum = 0; count = 0 }
        }
        return result
    }
}
