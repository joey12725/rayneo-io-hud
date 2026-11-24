package io.lampyr.glasses.ambient

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

@Serializable data class VoiceProfile(val id: String, val embedding: List<Float>)
@Serializable data class VoicePerson(val id: String, val name: String, val handle: String? = null,
    val me: Boolean = false, val muted: Boolean = false, val status: String = "confirmed", val blurb: String = "",
    val profiles: List<VoiceProfile> = emptyList())
@Serializable data class VoiceList(val model: String = "", val dim: Int = 256, val updatedAt: String = "",
    val people: List<VoicePerson> = emptyList())
data class RecognizedVoice(val person: VoicePerson, val profileId: String, val confidence: Float)

object SpeakerPolicy {
    const val DEFAULT_THRESHOLD = .55f
    const val MIN_THRESHOLD = .30f
    const val MAX_THRESHOLD = .90f
    const val MARGIN = .08f
    const val WINDOW_SAMPLES = 6 * 48000
    const val MIN_SAMPLES = 3 * 48000 / 2
    const val TICK_MS = 3000L
}

class SpeakerDecision {
    private var previous: String? = null
    fun reset() { previous = null }
    fun decide(vector: FloatArray, people: List<VoicePerson>, threshold: Float = SpeakerPolicy.DEFAULT_THRESHOLD): RecognizedVoice? {
        require(threshold.isFinite() && threshold in SpeakerPolicy.MIN_THRESHOLD..SpeakerPolicy.MAX_THRESHOLD)
        val unit = unit(vector) ?: run { reset(); return null }
        val scores = people.distinctBy { it.id }.mapNotNull { person ->
            person.profiles.mapNotNull profile@{ profile ->
                val ref = unit(profile.embedding.toFloatArray()) ?: return@profile null
                RecognizedVoice(person, profile.id, unit.indices.sumOf { unit[it].toDouble() * ref[it] }.toFloat().coerceIn(-1f, 1f))
            }.maxByOrNull { it.confidence }
        }.sortedByDescending { it.confidence }
        val best = scores.firstOrNull()
        if (best == null || best.confidence < threshold || best.confidence - (scores.getOrNull(1)?.confidence ?: -1f) < SpeakerPolicy.MARGIN) {
            reset(); return null
        }
        val stable = previous == best.person.id
        previous = best.person.id
        return best.takeIf { stable && !it.person.me && !it.person.muted }
    }
    companion object {
        fun unit(v: FloatArray): FloatArray? {
            if (v.size != 256 || v.any { !it.isFinite() }) return null
            val norm = sqrt(v.sumOf { it.toDouble() * it })
            if (norm <= 1e-12) return null
            return FloatArray(v.size) { (v[it] / norm).toFloat() }
        }
    }
}
