package io.lampyr.glasses.settings

import android.content.Context
import io.lampyr.glasses.data.NotifyPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Who the companion works for and what its assistant is called, plus which integrations are on and the notification
 * policy. Stored as JSON in the `companion-config` preferences, edited on the phone's Settings page, and seeded from
 * the gateway's `GET /glasses/v1/config` (see [CompanionConfigStore.seed]). Nothing here is secret; the gateway URL and
 * token stay in [AppSettings].
 */
@Serializable
data class CompanionConfig(
    /** The assistant's display name (gateway `assistant.name`). */
    val assistantName: String = DEFAULT_ASSISTANT,
    /** Label of the main agent a voice call hands work to; blank = "Main <assistantName>". */
    val workerName: String = "",
    /** Name of the gateway's notification classifier persona (gateway `assistant.workerName`). */
    val classifierName: String = DEFAULT_CLASSIFIER,
    /** Who wears the glasses (gateway `owner.name`). Used in agent text; "the user" when unset. */
    val ownerName: String = DEFAULT_OWNER,
    /** Other names and handles the owner goes by (gateway `owner.aliases`), matched case-insensitively. */
    val ownerAliases: List<String> = emptyList(),
    /** Integration toggles by id (see [INTEGRATIONS]); a missing id is on. Off hides that app on the glasses. */
    val integrations: Map<String, Boolean> = emptyMap(),
    /** Notification policy JSON merged over [NotifyPolicy.DEFAULT]; blank = the default policy. */
    val notifyPolicy: String = "",
    /** When the gateway last seeded this config (epoch ms), 0 = never. */
    val seededAt: Long = 0,
) {
    val worker get() = workerName.ifBlank { "Main $assistantName" }
    fun integrationOn(id: String) = integrations[id] != false

    /** The owner's name and aliases that can be matched in text; generic placeholders ("the user") are left out. */
    val ownerNames: List<String>
        get() = (listOf(ownerName) + ownerAliases).map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() !in GENERIC_OWNERS }
            .map { it.lowercase() }.distinct()
    /** Same pattern as the gateway's ownerPattern (lib/persona.mjs); null when no name is configured. */
    val ownerPattern: Regex? get() = ownerNames.takeIf { it.isNotEmpty() }?.let { names ->
        Regex("(?<![\\p{L}\\p{N}_])(?:${names.joinToString("|") { Regex.escape(it) }})(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    }

    /** The parsed policy; an invalid stored document falls back to the default (Settings refuses to save one). */
    val policy: NotifyPolicy get() = policyCache(notifyPolicy)

    companion object {
        const val DEFAULT_ASSISTANT = "Hermes"
        const val DEFAULT_CLASSIFIER = "Triage"
        const val DEFAULT_OWNER = "the user"
        private val GENERIC_OWNERS = setOf("the user", "user", "me", "owner", "the owner")

        /** Integration ids, as the gateway reports them in GET /glasses/v1/config `integrations`, with labels. */
        val INTEGRATIONS = linkedMapOf(
            "hermes" to "Hermes (assistant)",
            "beeper" to "Beeper",
            "t3" to "T3 Code",
            "home-assistant" to "Home Assistant",
            "calendar" to "Calendar",
            "spotify" to "Spotify",
            "youtube" to "YouTube",
            "audiobooks" to "Audiobookshelf",
            "people" to "People",
            "report" to "Morning report",
        )

        /** Launcher app id -> the integrations it needs (any one of them). Apps not listed are always shown. */
        val APP_INTEGRATIONS = mapOf(
            "joy" to listOf("hermes"), "beeper" to listOf("beeper"), "t3" to listOf("t3"), "ha" to listOf("home-assistant"),
            "calendar" to listOf("calendar"), "people" to listOf("people"), "report" to listOf("report"),
            "media" to listOf("spotify", "youtube", "audiobooks"),
        )

        /** The process-wide config, loaded by [CompanionConfigStore]; defaults until a store has loaded it. */
        @Volatile var current: CompanionConfig = CompanionConfig()

        private var cachedPolicy: Pair<String, NotifyPolicy>? = null
        @Synchronized private fun policyCache(json: String): NotifyPolicy {
            cachedPolicy?.let { (key, value) -> if (key == json) return value }
            val parsed = if (json.isBlank()) NotifyPolicy.DEFAULT else runCatching { NotifyPolicy.parse(json) }.getOrDefault(NotifyPolicy.DEFAULT)
            cachedPolicy = json to parsed
            return parsed
        }
    }
}

/** Short accessors for display strings. */
object Names {
    val assistant get() = CompanionConfig.current.assistantName.ifBlank { CompanionConfig.DEFAULT_ASSISTANT }
    val worker get() = CompanionConfig.current.worker
    val classifier get() = CompanionConfig.current.classifierName.ifBlank { CompanionConfig.DEFAULT_CLASSIFIER }
    val owner get() = CompanionConfig.current.ownerName.ifBlank { CompanionConfig.DEFAULT_OWNER }
}

/** Persists [CompanionConfig] in the `companion-config` preferences and publishes it as [CompanionConfig.current]. */
class CompanionConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun load(): CompanionConfig = prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(CompanionConfig.serializer(), it) }.getOrNull() }
        ?: CompanionConfig()

    /** Loads the stored config into [CompanionConfig.current]. */
    fun publish(): CompanionConfig = load().also { CompanionConfig.current = it }

    fun save(config: CompanionConfig) {
        if (config.notifyPolicy.isNotBlank()) NotifyPolicy.parse(config.notifyPolicy) // refuse an invalid policy
        prefs.edit().putString(KEY, json.encodeToString(CompanionConfig.serializer(), config)).apply()
        CompanionConfig.current = config
    }

    fun reset() { prefs.edit().remove(KEY).apply(); CompanionConfig.current = CompanionConfig() }

    /**
     * Applies the gateway's public config (GET /glasses/v1/config): assistant and owner names, integrations and the
     * notification policy. Returns the new config. Fields the gateway does not send keep their current value.
     */
    fun seed(body: JsonObject, now: Long = System.currentTimeMillis()): CompanionConfig {
        val updated = fromGateway(load(), body, now)
        save(updated)
        return updated
    }

    companion object {
        const val PREFS = "companion-config"
        private const val KEY = "config"

        fun fromGateway(base: CompanionConfig, body: JsonObject, now: Long): CompanionConfig {
            fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifBlank { null }
            val assistant = body["assistant"] as? JsonObject
            val owner = body["owner"] as? JsonObject
            val aliases = (owner?.get("aliases") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.ifBlank { null } }
            val on = (body["integrations"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
            val policy = (body["notifyPolicy"] as? JsonObject)?.let { doc ->
                runCatching { NotifyPolicy.from(JsonObject(doc.filterKeys { !it.startsWith("$") })).toJson().toString() }.getOrNull()
            }
            return base.copy(
                assistantName = assistant?.str("name") ?: base.assistantName,
                classifierName = assistant?.str("workerName") ?: base.classifierName,
                ownerName = owner?.str("name") ?: base.ownerName,
                ownerAliases = aliases ?: base.ownerAliases,
                integrations = on?.let { ids -> CompanionConfig.INTEGRATIONS.keys.associateWith { it in ids } } ?: base.integrations,
                notifyPolicy = policy ?: base.notifyPolicy,
                seededAt = now,
            )
        }
    }
}
