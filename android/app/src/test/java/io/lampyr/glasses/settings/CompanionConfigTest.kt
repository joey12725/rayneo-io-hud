package io.lampyr.glasses.settings

import io.lampyr.glasses.data.FakeDataStore
import io.lampyr.glasses.data.NotificationRules
import io.lampyr.glasses.data.NoticeInput
import io.lampyr.glasses.ui.Ui
import io.lampyr.glasses.ui.screens.appEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The typed companion config: defaults, persistence, seeding from GET /glasses/v1/config, and what it drives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionConfigTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun reset() { CompanionConfigStore(context).reset() }

    /** The shape server/gateway/lib/config.mjs publicConfig() returns, plus routes/config.mjs notifyPolicy. */
    private val gatewayBody = Json.parseToJsonElement("""
        {"schema":1,"assistant":{"name":"Atlas","workerName":"Sorter"},"owner":{"name":"Robin","aliases":["rob","robin_k"]},
         "tz":"UTC","integrations":["hermes","beeper","t3"],"targets":[{"id":"notes","name":"Notes folder","kind":"directory"}],
         "routes":{"memo":["notes"]},
         "notifyPolicy":{"schema":1,"kinds":{"beeper":true,"t3":true,"hermes":true,"assistant":true,"call":true,"calendar":false,"clock":true},
           "packages":{"beeper":["com.beeper.android"],"t3":["com.t3tools.t3code"],"dialer":["com.android.dialer"]},
           "apps":{"com.example.bank":"popup"},"deny":{},"beeperNoticeChannels":[],"t3LongRunMinutes":5,"dating":{"enabled":false,"apps":{}}}}
    """).jsonObject

    @Test fun defaultsAreGenericAndNameTheNativeAssistant() {
        val c = CompanionConfig()
        assertEquals("Hermes", c.assistantName); assertEquals("Main Hermes", c.worker); assertEquals("Triage", c.classifierName)
        assertEquals("the user", c.ownerName); assertNull("no owner name, no pattern", c.ownerPattern)
        assertTrue(CompanionConfig.INTEGRATIONS.keys.all(c::integrationOn))
        assertEquals("Hermes", Names.assistant)
    }

    @Test fun gatewayConfigSeedsNamesIntegrationsAndPolicy() {
        val store = CompanionConfigStore(context)
        val seeded = store.seed(gatewayBody, now = 42)
        assertEquals("Atlas", seeded.assistantName); assertEquals("Sorter", seeded.classifierName)
        assertEquals("Robin", seeded.ownerName); assertEquals(listOf("rob", "robin_k"), seeded.ownerAliases)
        assertEquals(42, seeded.seededAt)
        assertTrue(seeded.integrationOn("beeper")); assertFalse(seeded.integrationOn("home-assistant")); assertFalse(seeded.integrationOn("spotify"))
        assertEquals("popup", seeded.policy.apps["com.example.bank"]); assertFalse(seeded.policy.kindEnabled("calendar"))
        // Persisted and published process-wide.
        assertEquals(seeded, store.load()); assertEquals(seeded, CompanionConfig.current)
        assertEquals("Atlas", Names.assistant); assertEquals("Main Atlas", Names.worker)
        // The owner's names exempt a promo-looking Beeper message that names them; whole words only.
        val pattern = seeded.ownerPattern!!
        assertTrue(pattern.containsMatchIn("Rob, 30% off for you")); assertFalse(pattern.containsMatchIn("Robert's sale"))
        val promo = NoticeInput(kind = "beeper", source = "com.beeper.android", title = "Shop", body = "Rob, 30% off today")
        assertNull("names the owner: semantic, not promo", NotificationRules.decide(promo))
        assertEquals("beeper.promo", NotificationRules.decide(promo.copy(body = "30% off today"))!!.reason)
        // A local edit survives; a later seed only replaces what the gateway sends.
        store.save(seeded.copy(workerName = "Desk"))
        assertEquals("Desk", store.seed(buildJsonObject { put("schema", 1) }).workerName)
    }

    @Test fun integrationsHideTheirAppsOnTheGlasses() {
        val ui = Ui(FakeDataStore(context), scope = CoroutineScope(Dispatchers.Unconfined))
        try {
            assertTrue(appEntries(ui).any { it.id == "ha" } && appEntries(ui).any { it.id == "media" })
            CompanionConfigStore(context).save(CompanionConfig(integrations = mapOf("home-assistant" to false, "spotify" to false, "youtube" to false, "audiobooks" to true)))
            val ids = appEntries(ui).map { it.id }
            assertFalse("ha" in ids); assertTrue("media stays while one media integration is on", "media" in ids)
            assertEquals("Hermes", appEntries(ui).first { it.id == "joy" }.label)
            CompanionConfigStore(context).save(CompanionConfig(assistantName = "Atlas"))
            assertEquals("Atlas", appEntries(ui).first { it.id == "joy" }.label)
        } finally { ui.close() }
    }

    @Test fun anInvalidPolicyIsRefusedAndNeverBreaksRouting() {
        val store = CompanionConfigStore(context)
        assertThrows(IllegalArgumentException::class.java) { store.save(CompanionConfig(notifyPolicy = """{"apps":{"com.x":"loud"}}""")) }
        assertEquals(CompanionConfig(), store.load())
        // A bad stored document (written by an older build) falls back to the default policy.
        CompanionConfig.current = CompanionConfig(notifyPolicy = "not json")
        assertTrue(NotificationRules.allowedPackage("com.beeper.android")); assertFalse(NotificationRules.allowedPackage("com.tinder"))
    }
}
