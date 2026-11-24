package io.lampyr.glasses.render.avatar

import kotlin.math.max
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

class AvatarPackException(message: String) : Exception(message)

/** How a clip plays once its frames run out (same semantics the simulator and HUD use). */
enum class Playback(val wire: String) {
    LOOP("loop"),
    /** Loops, for ambient moods; plays like [LOOP]. */
    SUBTLE_LOOP("subtle_loop"),
    /** Loops for `boundedLoopSeconds` (default 3 s) after the state starts, then holds the last frame. */
    BOUNDED_LOOP("bounded_loop"),
    /** Plays through once and holds the last frame. */
    ONCE("once"),
    /** Plays through once and holds the last frame (kept distinct so packs can say what they mean). */
    ONCE_THEN_HOLD("once_then_hold");

    companion object {
        fun parse(value: String): Playback =
            entries.firstOrNull { it.wire == value } ?: throw AvatarPackException("unknown playback \"$value\"")
    }
}

data class AvatarFrame(val cell: Int, val ms: Int)

data class AvatarClip(val playback: Playback, val frames: List<AvatarFrame>, val boundedLoopSeconds: Double = 3.0) {
    val totalSeconds: Double get() = frames.sumOf { it.ms } / 1000.0

    /** Index into [frames] for a clip that started at [since] (seconds), drawn at [now]. */
    fun frameIndex(since: Double, now: Double): Int {
        val elapsed = max(0.0, now - since)
        val loops = playback == Playback.LOOP || playback == Playback.SUBTLE_LOOP ||
            (playback == Playback.BOUNDED_LOOP && elapsed < boundedLoopSeconds)
        var k = if (loops) elapsed % totalSeconds else elapsed
        frames.forEachIndexed { i, f ->
            val d = f.ms / 1000.0
            if (k < d) return i
            k -= d
        }
        return frames.lastIndex
    }
}

/**
 * Avatar pack manifest, schema v1:
 * `{schema:1, id, name, license, cell:{w,h}, atlas:{file, cols}, portraitCrop:[x,y,w,h], threshold,
 *   states:{<state>:{playback, boundedLoopSeconds?, frames:[{cell, ms}]}}, fallbacks:{<state>:<state>}}`.
 */
data class AvatarManifest(
    val id: String,
    val name: String,
    val license: String,
    val cellW: Int,
    val cellH: Int,
    val atlasFile: String,
    val cols: Int,
    val portraitCrop: List<Int>,
    val threshold: Int,
    val states: Map<String, AvatarClip>,
    val fallbacks: Map<String, String> = emptyMap(),
    val schema: Int = 1,
) {
    val cellCount: Int get() = states.values.maxOf { c -> c.frames.maxOf { it.cell } } + 1
    val rows: Int get() = (cellCount + cols - 1) / cols

    /**
     * The state this pack actually shows for [state]: itself when present, else the pack's `fallbacks`, then the
     * app's built-in chain ([AvatarStates.BUILTIN_FALLBACKS]), then `idle`, then the first state in the pack.
     */
    fun resolve(state: String): String {
        val seen = HashSet<String>()
        var s: String? = state
        while (s != null && seen.add(s)) {
            if (s in states) return s
            s = fallbacks[s] ?: AvatarStates.BUILTIN_FALLBACKS[s]
        }
        return if (AvatarStates.IDLE in states) AvatarStates.IDLE else states.keys.first()
    }

    fun clip(state: String): AvatarClip = states.getValue(resolve(state))

    /** Atlas cell to draw for [state] started at [since], at [now] (seconds). */
    fun frameCell(state: String, since: Double, now: Double): Int = clip(state).let { it.frames[it.frameIndex(since, now)].cell }

    companion object {
        const val SCHEMA = 1
        const val MAX_CELLS = 512
        private val ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val STATE = Regex("[a-z][a-z0-9_]{0,47}")

        fun parse(text: String): AvatarManifest {
            val root = try { Json.parseToJsonElement(text) } catch (e: Exception) { throw AvatarPackException("manifest is not JSON: ${e.message}") }
            return parse(root as? JsonObject ?: throw AvatarPackException("manifest must be an object"))
        }

        fun parse(o: JsonObject): AvatarManifest {
            val schema = o.int("schema") ?: throw AvatarPackException("missing schema")
            if (schema != SCHEMA) throw AvatarPackException("unsupported schema $schema (this app reads schema $SCHEMA)")
            val id = o.str("id") ?: throw AvatarPackException("missing id")
            if (!ID.matches(id)) throw AvatarPackException("id must be lowercase letters, digits, '.', '_' or '-' (max 64)")
            val cell = o["cell"] as? JsonObject ?: throw AvatarPackException("missing cell")
            val cw = cell.int("w") ?: 0
            val ch = cell.int("h") ?: 0
            if (cw !in 8..1024 || ch !in 8..1024) throw AvatarPackException("cell w/h must be 8..1024")
            val atlas = o["atlas"] as? JsonObject ?: throw AvatarPackException("missing atlas")
            val file = atlas.str("file") ?: "atlas.png"
            if (file.contains('/') || file.contains('\\') || file.startsWith(".") || !file.lowercase().endsWith(".png"))
                throw AvatarPackException("atlas.file must be a .png file name next to manifest.json")
            val cols = atlas.int("cols") ?: throw AvatarPackException("missing atlas.cols")
            if (cols !in 1..64) throw AvatarPackException("atlas.cols must be 1..64")
            val statesObj = o["states"] as? JsonObject ?: throw AvatarPackException("missing states")
            if (statesObj.isEmpty()) throw AvatarPackException("states is empty")
            val states = linkedMapOf<String, AvatarClip>()
            statesObj.forEach { (name, value) ->
                if (!STATE.matches(name)) throw AvatarPackException("bad state name \"$name\"")
                val s = value as? JsonObject ?: throw AvatarPackException("state $name must be an object")
                val playback = Playback.parse(s.str("playback") ?: "loop")
                val frames = (s["frames"] as? JsonArray ?: throw AvatarPackException("state $name has no frames")).map { f ->
                    val fo = f as? JsonObject ?: throw AvatarPackException("state $name: frame must be an object")
                    val c = fo.int("cell") ?: throw AvatarPackException("state $name: frame needs cell")
                    val ms = fo.int("ms") ?: throw AvatarPackException("state $name: frame needs ms")
                    if (c !in 0 until MAX_CELLS) throw AvatarPackException("state $name: cell $c out of range")
                    if (ms !in 1..60_000) throw AvatarPackException("state $name: ms must be 1..60000")
                    AvatarFrame(c, ms)
                }
                if (frames.isEmpty()) throw AvatarPackException("state $name has no frames")
                val bound = s["boundedLoopSeconds"]?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: 3.0
                if (bound <= 0) throw AvatarPackException("state $name: boundedLoopSeconds must be > 0")
                states[name] = AvatarClip(playback, frames, bound)
            }
            val fallbacks = (o["fallbacks"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
            val crop = (o["portraitCrop"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: throw AvatarPackException("portraitCrop must be 4 integers") }
                ?: run { val m = minOf(cw, ch); listOf((cw - m) / 2, 0, m, m) }
            if (crop.size != 4 || crop[0] < 0 || crop[1] < 0 || crop[2] < 1 || crop[3] < 1 || crop[0] + crop[2] > cw || crop[1] + crop[3] > ch)
                throw AvatarPackException("portraitCrop [x,y,w,h] must lie inside the cell")
            val threshold = o.int("threshold") ?: 100
            if (threshold !in 1..255) throw AvatarPackException("threshold must be 1..255")
            return AvatarManifest(id, o.str("name") ?: id, o.str("license") ?: "unspecified", cw, ch, file, cols, crop, threshold, states, fallbacks, schema)
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.doubleOrNull?.takeIf { it % 1.0 == 0.0 }?.toInt() }
    }
}
