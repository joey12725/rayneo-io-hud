package io.lampyr.glasses.glasses

import io.lampyr.glasses.glasses.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Requirements 15.1 / 15.2 and look-up wake: stock settings commands, replies and launcher events. */
class StockSettingsProtocolTest {
    private fun body(command: Command): JsonObject {
        assertEquals(0x0f, command.business)
        assertEquals(5, command.envelope.type)
        return Json.parseToJsonElement(command.envelope.json).jsonObject
    }
    private fun List<Command>.json() = map { it.envelope.json }
    private fun parse(type: Int, json: String) = EventParser.parse(RayneoFrame(0x0f, 0, Envelope(type, json, null).encode()))

    @Test fun wakeupConfigCarriesAllThreeFieldsAsAJsonString() {
        val b = body(Commands.wakeupConfig(true, 35, 2))
        assertEquals("wakeup_config", b["cmd"]!!.jsonPrimitive.content)
        val payload = b["payload"]!!.jsonObject
        assertEquals(0, payload["value"]!!.jsonPrimitive.int)
        assertEquals(0, payload["mode"]!!.jsonPrimitive.int)
        assertEquals("""{"headup_switch":1,"headup_degree":35,"crown_switch":2}""", payload["data"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { Commands.wakeupConfig(true, 91, 0) }
        assertThrows(IllegalArgumentException::class.java) { Commands.wakeupConfig(true, 30, 4) }
    }

    @Test fun autoLockTimeUsesExplicitNulls() {
        assertEquals("""{"cmd":"auto_lock_time","payload":{"value":5,"mode":null,"data":null}}""", Commands.autoLockTime(5).envelope.json)
        assertThrows(IllegalArgumentException::class.java) { Commands.autoLockTime(7) }
    }

    @Test fun headGesturesAndDisplayConfigShapes() {
        val g = body(Commands.headGestures(true, 1))["payload"]!!.jsonObject
        assertEquals(1, g["value"]!!.jsonPrimitive.int); assertEquals(1, g["mode"]!!.jsonPrimitive.int); assertEquals("", g["data"]!!.jsonPrimitive.content)
        val d = body(Commands.displayConfig(5, 1))
        assertEquals("display_config", d["cmd"]!!.jsonPrimitive.content)
        assertEquals("""{"height":5,"distance":1}""", d["payload"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { Commands.displayConfig(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { Commands.displayConfig(3, 3) }
    }

    @Test fun launcherEventsAreTypedNotUnhandled() {
        assertEquals(GlassesEvent.Screen(1), parse(5, """{"cmd":"screen_status","payload":{"value":1,"mode":0,"data":""}}"""))
        assertEquals(GlassesEvent.Screen(0), parse(5, """{"cmd":"screen_status","payload":{"value":0}}"""))
        assertEquals(GlassesEvent.Hall(1), parse(5, """{"cmd":"hall_state","payload":{"value":1}}"""))
        val status = parse(1, """{"generalStatus":{"battery":70,"brightness":4,"screenStatus":1}}""") as GlassesEvent.Status
        assertEquals(1, status.screen); assertEquals(70, status.battery)
        assertNull((parse(1, """{"generalStatus":{"battery":70}}""") as GlassesEvent.Status).screen)
    }

    private val reply = """{"generalSettings":{"autoLockTime":15,"wakeupConfig":{"headupSwitch":1,"headupDegree":25,"crownSwitch":1},
        "headGestures":{"enabled":0,"mode":1},"displayConfig":{"height":3,"distance":2},"crownConfig":{"direction":0}}}"""

    @Test fun generalSettingsReplyParsesEveryStructure() {
        val event = parse(4, reply) as GlassesEvent.Settings
        val s = StockSettings.parse(event.body)
        assertEquals(StockSettings(autoLockTime = 15, headUp = true, headUpDegrees = 25, crownSwitch = 1, headGestures = false, headGestureMode = 1,
            displayHeight = 3, displayDistance = 2, wakeKnown = true, gesturesKnown = true, displayKnown = true), s)
    }

    @Test fun partialOrOutOfRangeRepliesStayUnknown() {
        val s = StockSettings.parse(Json.parseToJsonElement("""{"wakeupConfig":{"headupSwitch":1,"headupDegree":25},"headGestures":{"enabled":7,"mode":0}}""").jsonObject)
        assertFalse(s.wakeKnown); assertFalse(s.gesturesKnown); assertFalse(s.displayKnown)
        assertThrows(IllegalStateException::class.java) { StockSettings.writes(s, s.copy(headUp = true)) }
    }

    @Test fun writesResendUnchangedFieldsOfTheChangedStructureOnly() {
        val from = StockSettings.parse((parse(4, reply) as GlassesEvent.Settings).body)
        val commands = StockSettings.writes(from, from.copy(headUpDegrees = 40))
        assertEquals(listOf(Commands.wakeupConfig(true, 40, 1)).json(), commands.json())
        assertTrue(StockSettings.writes(from, from).isEmpty())
        assertEquals(listOf(Commands.displayConfig(1, 2)).json(), StockSettings.writes(from, from.copy(displayHeight = 1)).json())
        assertEquals(listOf(Commands.headGestures(true, 1)).json(), StockSettings.writes(from, from.copy(headGestures = true)).json())
        assertEquals(10, StockSettings.nextDegrees(70)); assertEquals(30, StockSettings.nextDegrees(25)); assertEquals(10, StockSettings.nextDegrees(0))
    }

    @Test fun liveClientReadsBeforeWritingAndForgetsOnDisconnect() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<Command>()
        val client = StockSettingsClient { sent += it }
        assertFalse(client.update { it.copy(headUp = true) }.ok)
        assertTrue(sent.isEmpty())
        client.connectionChanged(true)
        client.received(parse(4, reply) as GlassesEvent.Settings)
        var notified = 0
        val stop = client.listen { notified++ }
        assertTrue(client.update { it.copy(headUp = false) }.ok)
        assertEquals(listOf(Commands.wakeupConfig(false, 25, 1), Commands.generalSettings()).json(), sent.json())
        assertFalse(client.current!!.headUp)
        client.connectionChanged(false)
        assertNull(client.current); assertTrue(notified >= 2)
        stop()
    }
}
