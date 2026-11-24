package io.lampyr.glasses.data

import kotlinx.serialization.json.*

/** Channel ids of one dating app (notify policy `dating.apps`); empty lists leave the app to the text rules. */
data class DatingChannels(
    val match: List<String> = emptyList(),
    val like: List<String> = emptyList(),
    val message: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
)

/**
 * The notification policy tables, the same document as the gateway's `apis/glasses/notify-policy.default.json`
 * (schema 1): which kinds are on, the Beeper / T3 Code / dialer package names, an `apps` allow-list (package -> route),
 * the deny list (reason slugs for packages that never leave the phone), Beeper's system channels, and the optional
 * dating preset (off by default). [DEFAULT] mirrors the gateway default exactly (NotifyPolicyTest compares them).
 *
 * A user policy (phone Settings, or seeded from GET /glasses/v1/config `notifyPolicy`) is merged over the default
 * with the gateway's rules: `kinds`, `packages` and `apps` merge per key; every other key replaces the default.
 */
data class NotifyPolicy(
    val kinds: Map<String, Boolean> = emptyMap(),
    val packages: Map<String, List<String>> = emptyMap(),
    /** Extra apps whose notifications reach the glasses: package -> popup | stream | suppress. */
    val apps: Map<String, String> = emptyMap(),
    val deny: Map<String, List<String>> = emptyMap(),
    val beeperNoticeChannels: List<String> = emptyList(),
    val t3LongRunMinutes: Double = 5.0,
    val datingEnabled: Boolean = false,
    val datingApps: Map<String, DatingChannels> = emptyMap(),
) {
    val beeperPackages get() = packages["beeper"].orEmpty()
    val t3Packages get() = packages["t3"].orEmpty()
    val dialerPackages get() = packages["dialer"].orEmpty()
    /** Dating apps the policy routes (only while the preset is enabled). */
    val activeDating: Map<String, DatingChannels> get() = if (datingEnabled) datingApps else emptyMap()
    fun kindEnabled(kind: String) = kinds[kind] != false

    fun toJson(): JsonObject = buildJsonObject {
        put("schema", 1)
        put("kinds", JsonObject(kinds.mapValues { JsonPrimitive(it.value) }))
        put("packages", JsonObject(packages.mapValues { strings(it.value) }))
        put("apps", JsonObject(apps.mapValues { JsonPrimitive(it.value) }))
        put("deny", JsonObject(deny.mapValues { strings(it.value) }))
        put("beeperNoticeChannels", strings(beeperNoticeChannels))
        put("t3LongRunMinutes", if (t3LongRunMinutes % 1.0 == 0.0) JsonPrimitive(t3LongRunMinutes.toLong()) else JsonPrimitive(t3LongRunMinutes))
        put("dating", buildJsonObject {
            put("enabled", datingEnabled)
            put("apps", JsonObject(datingApps.mapValues { (_, t) ->
                buildJsonObject { put("match", strings(t.match)); put("like", strings(t.like)); put("message", strings(t.message)); put("deny", strings(t.deny)) }
            }))
        })
    }

    companion object {
        private val APP_ROUTES = setOf("popup", "stream", "suppress")
        private val KNOWN = setOf("\$comment", "\$schema", "schema", "kinds", "packages", "apps", "deny", "beeperNoticeChannels", "t3LongRunMinutes", "dating")
        private fun strings(list: List<String>) = JsonArray(list.map(::JsonPrimitive))

        /** Mirror of server/gateway/apis/glasses/notify-policy.default.json. */
        val DEFAULT = NotifyPolicy(
            kinds = linkedMapOf("beeper" to true, "t3" to true, "hermes" to true, "assistant" to true, "call" to true, "calendar" to true, "clock" to true),
            packages = linkedMapOf(
                "beeper" to listOf("com.beeper.android"),
                "t3" to listOf("com.t3tools.t3code"),
                "dialer" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.android.server.telecom"),
            ),
            deny = linkedMapOf(
                "messaging" to listOf("com.google.android.apps.messaging", "com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.discord", "com.facebook.orca", "com.instagram.android", "com.snapchat.android", "com.Slack", "com.samsung.android.messaging", "com.textra"),
                "email" to listOf("com.google.android.gm", "com.microsoft.office.outlook", "com.superhuman.mail", "ch.protonmail.android"),
                "media" to listOf("com.google.android.youtube", "com.spotify.music", "com.google.android.apps.youtube.music"),
                "home" to listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal"),
                "assistant" to listOf("com.openai.chatgpt", "com.anthropic.claude", "ai.perplexity.app.android"),
            ),
            beeperNoticeChannels = listOf("APP_NOTICES", "IMPORTANT_NOTICES", "BRIDGE_DISCONNECT_NOTICES", "DOWNLOADS", "MEDIA_PLAYBACK", "REMINDERS", "URGENT", "SILENT_NOTIFICATION_CHANNEL_ID"),
            t3LongRunMinutes = 5.0,
            datingEnabled = false,
            datingApps = linkedMapOf(
                "com.tinder" to DatingChannels(listOf("MATCHES"), listOf("NEW_LIKES", "SUPER_LIKES"), listOf("MESSAGES"), listOf("MESSAGE_LIKES", "TOP_PICKS")),
                "co.hinge.app" to DatingChannels(listOf("matches"), listOf("likes"), listOf("messages"), listOf("announcements", "promotions")),
                "com.bumble.app" to DatingChannels(listOf("matches"), listOf("liked_you"), listOf("messages"), listOf("expiring", "survey", "event", "profile_tip", "goodstuff", "_system_", "_other_", "video_calls")),
                "co.feeld" to DatingChannels(listOf("new-match"), emptyList(), listOf("chat-messages"), listOf("PROACTIVE_MESSAGING_NOTIFICATION_CHANNEL_ID")),
                "com.okcupid.okcupid" to DatingChannels(), "com.grindrapp.android" to DatingChannels(), "com.match.android.matchmobile" to DatingChannels(),
                "com.pof.android" to DatingChannels(), "com.coffeemeetsbagel" to DatingChannels(), "com.ftw_and_co.happn" to DatingChannels(),
                "com.badoo.mobile" to DatingChannels(), "com.hily.app" to DatingChannels(), "com.raya.raya" to DatingChannels(), "com.her.android" to DatingChannels(),
                "com.takimi.android" to DatingChannels(),
            ),
        )

        /** Parses a policy document and merges it over [base]. Throws IllegalArgumentException listing every problem. */
        fun parse(text: String, base: NotifyPolicy? = DEFAULT): NotifyPolicy {
            val doc = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw IllegalArgumentException("notify policy must be a JSON object")
            return from(doc, base)
        }

        fun from(doc: JsonObject, base: NotifyPolicy? = DEFAULT): NotifyPolicy {
            val problems = mutableListOf<String>()
            doc.keys.filter { it !in KNOWN }.forEach { problems += "unknown key \"$it\"" }
            (doc["schema"] as? JsonPrimitive)?.let { if (it.intOrNull != 1) problems += "schema must be 1" }
            fun list(e: JsonElement?, what: String): List<String>? = when (e) {
                null, JsonNull -> null
                is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.also { if (it.size != e.size) problems += "$what must be a list of strings" }
                else -> { problems += "$what must be a list of strings"; null }
            }
            fun obj(key: String) = doc[key]?.let { it as? JsonObject ?: run { problems += "$key must be an object"; null } }
            val kinds = LinkedHashMap(base?.kinds.orEmpty())
            obj("kinds")?.forEach { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { kinds[k] = it } ?: run { problems += "kinds.$k must be true or false" } }
            val packages = LinkedHashMap(base?.packages.orEmpty())
            obj("packages")?.forEach { (k, v) -> list(v, "packages.$k")?.let { packages[k] = it } }
            for (k in listOf("beeper", "t3", "dialer")) packages.putIfAbsent(k, emptyList())
            val apps = LinkedHashMap(base?.apps.orEmpty())
            obj("apps")?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { it in APP_ROUTES }?.let { apps[k] = it } ?: run { problems += "apps[\"$k\"] must be popup, stream or suppress" } }
            val deny = obj("deny")?.let { d -> d.mapValues { (k, v) -> list(v, "deny.$k").orEmpty() } } ?: base?.deny.orEmpty()
            val channels = list(doc["beeperNoticeChannels"], "beeperNoticeChannels") ?: base?.beeperNoticeChannels.orEmpty()
            val longRun = doc["t3LongRunMinutes"]?.let { (it as? JsonPrimitive)?.doubleOrNull?.takeIf { v -> v >= 0 } ?: run { problems += "t3LongRunMinutes must be a number"; null } }
                ?: base?.t3LongRunMinutes ?: 5.0
            val dating = obj("dating")
            val enabled = dating?.get("enabled")?.let { (it as? JsonPrimitive)?.booleanOrNull ?: run { problems += "dating.enabled must be true or false"; null } }
                ?: base?.datingEnabled ?: false
            val datingApps = (dating?.get("apps") as? JsonObject)?.mapValues { (pkg, t) ->
                val table = t as? JsonObject ?: run { problems += "dating.apps[\"$pkg\"] must be an object"; return@mapValues DatingChannels() }
                table.keys.filter { it !in setOf("match", "like", "message", "deny") }.forEach { problems += "dating.apps[\"$pkg\"].$it must be one of match/like/message/deny" }
                DatingChannels(list(table["match"], "dating.apps[\"$pkg\"].match").orEmpty(), list(table["like"], "dating.apps[\"$pkg\"].like").orEmpty(),
                    list(table["message"], "dating.apps[\"$pkg\"].message").orEmpty(), list(table["deny"], "dating.apps[\"$pkg\"].deny").orEmpty())
            } ?: base?.datingApps.orEmpty()
            require(problems.isEmpty()) { problems.joinToString("\n") { "notify policy: $it" } }
            return NotifyPolicy(kinds, packages, apps, deny, channels, longRun, enabled, datingApps)
        }
    }
}
