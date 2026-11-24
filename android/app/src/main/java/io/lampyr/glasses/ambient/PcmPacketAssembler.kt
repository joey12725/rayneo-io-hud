package io.lampyr.glasses.ambient

import java.util.TreeMap

data class DecodedCapture(val packet: AmbientPacket, val pcm: ShortArray, val channels: Int)

/** MediaCodec may split/coalesce output buffers. Reassemble by sample PTS before deciding VAD
 * for an original packet, so the stored bytes and speech decision always describe the same audio.
 */
class PcmPacketAssembler {
    private data class Pending(val packet: AmbientPacket, val frames: Int, var pcm: ShortArray? = null,
                               var channels: Int = 0, var received: Int = 0)
    private val pending = TreeMap<Long, Pending>()
    val size get() = pending.size
    fun enqueue(packet: AmbientPacket, startSample: Long) { pending[startSample] = Pending(packet, OpusPacket.samples(packet.opus).toInt()) }
    fun reset() = pending.clear()
    fun decoded(pcm: ShortArray, channels: Int, ptsUs: Long): List<DecodedCapture> {
        require(channels in 1..2 && pcm.size % channels == 0)
        val result = mutableListOf<DecodedCapture>()
        val start = (ptsUs * 48000 + 500000) / 1000000
        var offset = 0
        while (offset < pcm.size / channels) {
            val sample = start + offset
            val entry = pending.floorEntry(sample) ?: pending.ceilingEntry(sample) ?: break
            val p = entry.value
            val destination = (sample - entry.key).toInt()
            if (destination < 0) { offset += minOf(-destination, pcm.size / channels - offset); continue }
            if (destination >= p.frames) { pending.remove(entry.key); continue }
            if (p.pcm == null) { p.pcm = ShortArray(p.frames * channels); p.channels = channels }
            require(p.channels == channels && destination == p.received) { "Non-contiguous decoded PCM" }
            val count = minOf(p.frames - destination, pcm.size / channels - offset)
            pcm.copyInto(p.pcm!!, destination * channels, offset * channels, (offset + count) * channels)
            p.received += count; offset += count
            if (p.received == p.frames) { pending.remove(entry.key); result += DecodedCapture(p.packet, p.pcm!!, channels) }
        }
        return result
    }
}
