package io.lampyr.glasses.ambient

import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.*

/** Exclusion intervals are half-open. An overlapping packet is excluded in full, never partly leaked. */
class ExclusionRanges {
    private val open = linkedMapOf<String, Long>()
    private val closed = mutableListOf<Pair<Long, Long>>()
    fun begin(id: String, utc: Long) { open[id] = utc }
    fun end(id: String, utc: Long) { open.remove(id)?.let { closed += it to maxOf(it, utc) } }
    fun overlaps(start: Long, end: Long): Boolean = open.values.any { it < end } || closed.any { it.first < end && it.second > start }
    fun prune(before: Long) { closed.removeAll { it.second < before } }
}

/** Dependency-free FALLBACK, intentionally simpler than a learned speech detector.
 * Energy gate + zero crossing rejection, adapting noise floor only outside speech, with hangover.
 */
class EnergyVad(private val hangoverMs: Long = 600) {
    private var noise = 120.0
    private var remaining = 0L
    fun reset() { noise = 120.0; remaining = 0 }
    fun speech(pcm: ShortArray, rate: Int = 48000, channels: Int = 2): Boolean {
        require(rate > 0 && channels in 1..2 && pcm.size % channels == 0)
        if (pcm.isEmpty()) return false
        // Choose the louder channel, avoiding phase cancellation between bone and room microphones.
        val selected = (0 until channels).maxBy { channel ->
            (channel until pcm.size step channels).sumOf { pcm[it].toDouble() * pcm[it] }
        }
        val mono = IntArray(pcm.size / channels) { pcm[it * channels + selected].toInt() }
        val rms = sqrt(mono.sumOf { it.toDouble() * it } / mono.size)
        val crossings = (1 until mono.size).count { (mono[it] < 0) != (mono[it - 1] < 0) }.toDouble() / mono.size
        val active = rms > max(350.0, noise * 2.8) && crossings in 0.005..0.45
        val ms = mono.size * 1000L / rate
        if (active) remaining = hangoverMs else {
            if (remaining == 0L) noise = noise * .97 + rms.coerceAtMost(500.0) * .03
            remaining = (remaining - ms).coerceAtLeast(0)
        }
        return active || remaining > 0
    }
    fun wearer(pcm: ShortArray, channels: Int): Boolean {
        if (channels != 2 || pcm.isEmpty()) return false
        var wearer = 0.0; var room = 0.0
        for (i in pcm.indices step 2) { wearer += pcm[i].toDouble().pow(2); room += pcm[i + 1].toDouble().pow(2) }
        return wearer > room * 2.25 && wearer / (pcm.size / 2) > 350.0.pow(2)
    }
}

@Serializable data class GeoFix(val conversationId: String, val utc: Long, val lat: Double? = null, val lon: Double? = null,
    val accuracy: Float? = null, val speedMps: Float? = null, val heading: Float? = null, val motion: String = "unknown",
    val placeLabel: String = "", val placeDistanceMi: Double? = null, val road: String = "",
    val source: String, val confidence: String, val role: String = "", val observedUtc: Long? = null)

object LocationPolicy {
    const val MOVED_METERS = 160.9344
    fun reusable(fix: GeoFix, now: Long) = now - fix.utc in 0..120_000 && fix.accuracy != null && fix.accuracy <= 50f && fix.lat != null && fix.lon != null
    fun distance(a: GeoFix, b: GeoFix): Double {
        if (a.lat == null || a.lon == null || b.lat == null || b.lon == null) return 0.0
        val lat = Math.toRadians(b.lat - a.lat); val lon = Math.toRadians(b.lon - a.lon)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
        return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    fun during(last: GeoFix, candidate: GeoFix) = distance(last, candidate) > MOVED_METERS ||
        (candidate.motion != "unknown" && candidate.motion != last.motion)
    fun motion(speed: Float?, recentSteps: Boolean): String = when {
        recentSteps -> "walking"
        speed == null -> "unknown"
        speed < .7f -> "still"
        speed < 2.2f -> "walking"
        speed < 7f -> "bicycle" // speed heuristic, not a confident activity classifier
        else -> "vehicle"
    }
}

/** Recognition requires confirmed enrolment; default has no side effects and never updates People. */
fun interface RecentSpeakerRecognizer {
    val ready: Boolean get() = true
    fun recent(pcm: ShortArray, channels: Int, utc: Long): SpeakerMatch
    fun reset() {}
    /** True while an inference is running; burst-fed callers wait instead of having windows skipped. */
    val busy: Boolean get() = false
}
data class SpeakerMatch(val enrolledId: String? = null, val confidence: Float = 0f, val uncertain: Boolean = true)
object NoSpeakerRecognition : RecentSpeakerRecognizer { override val ready = false; override fun recent(pcm: ShortArray, channels: Int, utc: Long) = SpeakerMatch() }
