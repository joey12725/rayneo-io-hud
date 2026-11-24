package io.lampyr.glasses.agent

import io.lampyr.glasses.agent.ops.*
import io.lampyr.glasses.calls.FakeCalls
import io.lampyr.glasses.ui.ListView
import io.lampyr.glasses.ui.ReaderView
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Memo, Docs, Teleprompter, People, Health, Calendar, Report, Home Assistant and Lifelog ops on FakeDataStore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UtilityOpsTest {
    @Test fun memoStartListReadStop(): Unit = ActionRegistryTest.Harness(listOf(MemoOps)).use { h ->
        val list = h.call("memo.list").data!!.jsonObject
        assertFalse(list["recording"]!!.jsonPrimitive.boolean)
        val memos = list["memos"]!!.jsonArray
        assertEquals(h.ui.data.memos.snapshot.list.size.coerceAtMost(10), memos.size)
        val read = h.call("memo.read", mapOf("memo" to "grocery list"))
        assertTrue(read.message, read.ok)
        assertEquals("Grocery list and Saturday plan", read.data!!.jsonObject["title"]!!.jsonPrimitive.content)

        assertEquals(Codes.UNAVAILABLE, h.call("memo.stop").code)
        // Starting records the room: confirmed outside the glasses conversation.
        assertTrue(h.registry.needsConfirm(h.registry.ops["memo.start"]!!, Origin.OTHER))
        assertTrue(h.call("memo.start", origin = Origin.GLASSES_TURN).ok)
        assertTrue(h.ui.data.memos.snapshot.recording)
        assertTrue(h.call("memo.list").data!!.jsonObject["recording"]!!.jsonPrimitive.boolean)
        assertEquals(Codes.UNAVAILABLE, h.call("memo.start", origin = Origin.GLASSES_TURN).code)
        // Stopping never asks.
        assertFalse(h.registry.needsConfirm(h.registry.ops["memo.stop"]!!, Origin.OTHER))
        val stopped = h.call("memo.stop")
        assertTrue(stopped.message, stopped.ok)
        assertFalse(h.ui.data.memos.snapshot.recording)
        assertTrue(h.call("memo.toggle", origin = Origin.GLASSES_TURN).ok)
        assertTrue(h.ui.data.memos.snapshot.recording)
        h.call("memo.toggle", origin = Origin.GLASSES_TURN)
        // A phone call owns the microphone.
        (h.ui.data.calls as FakeCalls).incoming()
        assertEquals("Microphone reserved for phone call", h.call("memo.start", origin = Origin.GLASSES_TURN).message)
    }

    @Test fun docsListReadOpenScroll(): Unit = ActionRegistryTest.Harness(listOf(DocsOps)).use { h ->
        val docs = h.call("docs.list").data!!.jsonArray
        assertEquals(h.ui.data.docs.documents.size, docs.size)
        val read = h.call("docs.read", mapOf("doc" to "waveguide")).data!!.jsonObject
        assertEquals("Waveguide displays explainer", read["title"]!!.jsonPrimitive.content)
        assertTrue(read["text"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(Codes.UNAVAILABLE, h.call("docs.scroll", mapOf("pages" to 1)).code)
        val opened = h.call("docs.open", mapOf("doc" to "Waveguide displays explainer"))
        assertTrue(opened.message, opened.ok)
        val reader = h.ui.top as ReaderView
        assertEquals("doc", reader.id)
        assertEquals("Waveguide displays explainer", reader.title)
        assertEquals("docs", AgentScreen.currentApp(h.ui))
        assertTrue(h.call("docs.scroll", mapOf("pages" to 1)).ok)
        assertTrue(h.call("docs.scroll", mapOf("pages" to -5)).ok)
        assertEquals(0, reader.page)
    }

    @Test fun teleprompterStartScrollStop(): Unit = ActionRegistryTest.Harness(listOf(TeleprompterOps)).use { h ->
        assertTrue(h.call("teleprompter.read_script").data!!.jsonObject["script"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(Codes.UNAVAILABLE, h.call("teleprompter.stop").code)
        assertTrue(h.call("teleprompter.start").ok)
        val tp = h.ui.top as Teleprompter
        assertTrue(tp.auto)
        assertTrue(tp.keepAwake)
        assertTrue(h.call("teleprompter.scroll", mapOf("lines" to 2)).ok)
        assertEquals(2, tp.off)
        assertTrue(h.call("teleprompter.stop").ok)
        assertFalse(tp.auto)
        assertTrue(h.call("teleprompter.start").ok)
        assertSame(tp, h.ui.top) // starting again reuses the open teleprompter
        h.ui.goHome()
        assertTrue(h.call("teleprompter.open").ok)
        assertFalse((h.ui.top as Teleprompter).auto)
    }

    @Test fun peopleRecentLookupMuteOpen(): Unit = ActionRegistryTest.Harness(listOf(PeopleOps)).use { h ->
        val recent = h.call("people.recent").data!!.jsonObject
        assertTrue(recent["listening"]!!.jsonPrimitive.boolean)
        assertEquals(2, recent["heard"]!!.jsonArray.size)
        val card = h.call("people.lookup", mapOf("person" to "dana")).data!!.jsonObject
        assertEquals("Dana Kim", card["name"]!!.jsonPrimitive.content)
        assertTrue(card["notes"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(h.call("people.mute", mapOf("person" to "Marcus Lee")).ok)
        assertTrue(h.ui.data.people.snapshot.recent.first { it.name == "Marcus Lee" }.muted)
        assertTrue(h.call("people.recent").data!!.jsonObject["recent"]!!.jsonArray.none { it.jsonObject["name"]!!.jsonPrimitive.content == "Marcus Lee" })
        assertTrue(h.call("people.mute", mapOf("person" to "Marcus", "muted" to false)).ok)
        assertFalse(h.ui.data.people.snapshot.recent.first { it.name == "Marcus Lee" }.muted)
        assertTrue(h.call("people.open", mapOf("person" to "Priya")).ok)
        assertTrue(h.ui.top is PersonCard)
        // A second card replaces the first (same view id): the glasses must show the person the result names.
        val priya = h.ui.top
        assertTrue(h.call("people.open", mapOf("person" to "dana")).ok)
        assertNotSame(priya, h.ui.top)
        val danaNotes = (h.ui.data.people.snapshot.recent + h.ui.data.people.snapshot.heard).first { it.name == "Dana Kim" }.notes
        assertTrue((h.ui.top as PersonCard).textContent().contains(danaNotes))
        h.ui.listening.set(false)
        assertEquals(0, h.call("people.recent").data!!.jsonObject["heard"]!!.jsonArray.size)
    }

    @Test fun healthOverviewHistoryAndChart(): Unit = ActionRegistryTest.Harness(listOf(HealthOps)).use { h ->
        val metrics = h.call("health.overview").data!!.jsonObject["metrics"]!!.jsonArray
        assertTrue(metrics.any { it.jsonObject["key"]!!.jsonPrimitive.content == "readiness" })
        val history = h.call("health.metric_history", mapOf("metric" to "readiness", "days" to 3)).data!!.jsonObject
        val samples = history["samples"]!!.jsonArray
        assertEquals(3, samples.size)
        val readiness = h.ui.data.health.metrics.first { it.key == "readiness" }
        assertEquals(readiness.series.takeLast(3).map { it.value }, samples.map { it.jsonObject["value"]!!.jsonPrimitive.double })
        assertNotNull(history["avg"])
        assertEquals(Codes.FAILED, h.call("health.metric_history", mapOf("metric" to "zzz")).code)
        assertTrue(h.call("health.open_metric", mapOf("metric" to "hrv", "days" to 30)).ok)
        val detail = h.ui.top as HealthDetail
        assertEquals(30, detail.range)
        assertTrue(h.ui.stack.any { it is Health })
    }

    @Test fun calendarAgendaAndOpenDay(): Unit = ActionRegistryTest.Harness(listOf(CalendarOps)).use { h ->
        // The harness clock is Wed Nov 11, 7:45 PM in Chicago.
        val agenda = h.call("calendar.agenda", mapOf("days" to 2)).data!!.jsonArray
        assertEquals(listOf("2026-11-11", "2026-11-12"), agenda.map { it.jsonObject["date"]!!.jsonPrimitive.content })
        assertEquals(3, agenda[1].jsonObject["events"]!!.jsonArray.size)
        assertEquals(3, h.call("calendar.agenda").data!!.jsonArray.size)
        val opened = h.call("calendar.open_day", mapOf("day" to "tomorrow"))
        assertTrue(opened.message, opened.ok)
        val list = h.ui.top as CalendarScreen
        val rows = list.rows()
        val header = rows.indexOfFirst { it.sep && it.label == "TOMORROW" }
        assertEquals(header + 1, list.cur)
        assertTrue(h.call("calendar.open_day", mapOf("day" to "friday")).ok)
        assertEquals(rows.indexOfFirst { it.sep && it.label == "FRI NOV 13" } + 1, list.cur)
        assertSame(list, h.ui.top)
        assertEquals(Codes.UNAVAILABLE, h.call("calendar.open_day", mapOf("day" to "2027-01-01")).code)
        assertEquals(Codes.INVALID_ARGS, h.call("calendar.open_day", mapOf("day" to "someday")).code)
    }

    @Test fun reportLatestAndOpenAtACard(): Unit = ActionRegistryTest.Harness(listOf(ReportOps)).use { h ->
        val report = h.call("report.latest").data!!.jsonObject
        val cards = report["cards"]!!.jsonArray
        assertEquals(h.ui.data.report.snapshot.cards.size, cards.size)
        assertTrue(h.call("report.open", mapOf("card" to "ai news")).ok)
        val screen = h.ui.top as Report
        assertEquals(h.ui.data.report.snapshot.cards.indexOfFirst { it.heading == "AI news" }, screen.i)
        assertTrue(h.call("report.open").ok)
        assertEquals(0, screen.i)
        assertEquals(Codes.FAILED, h.call("report.open", mapOf("card" to "horoscope")).code)
    }

    @Test fun homeAssistantPanelScenesAndAlarm(): Unit = ActionRegistryTest.Harness(listOf(HomeAssistantOps)).use { h ->
        val panel = h.call("ha.panel").data!!.jsonObject
        assertEquals(3, panel["scenes"]!!.jsonArray.size)
        assertEquals(3, panel["printers"]!!.jsonArray.size)
        // Scenes act on the house: confirm card outside the glasses conversation.
        val pending = h.callAsync("ha.run_scene", mapOf("scene" to "going to sleep"))
        assertTrue(h.ui.top is AgentConfirm)
        h.input.press("y"); h.input.release("y")
        val ran = runBlocking { pending.await() }
        assertTrue(ran.message, ran.ok)
        assertEquals("Going to sleep · done", ran.message)
        assertTrue(h.call("ha.set_alarm", mapOf("time" to "8:30 am"), Origin.GLASSES_TURN).ok)
        assertEquals("8:30 AM", h.ui.data.ha.panel.alarm.time)
        assertTrue(h.call("ha.set_alarm", mapOf("style" to "Harsh"), Origin.GLASSES_TURN).ok)
        assertEquals("Harsh", h.ui.data.ha.panel.alarm.style)
        assertEquals(Codes.INVALID_ARGS, h.call("ha.set_alarm", mapOf("time" to "25:00"), Origin.GLASSES_TURN).code)
        assertEquals(Codes.INVALID_ARGS, h.call("ha.set_alarm", emptyMap(), Origin.GLASSES_TURN).code)
    }

    @Test fun lifelogStatusPauseResume(): Unit = ActionRegistryTest.Harness(listOf(LifelogOps)).use { h ->
        assertTrue(h.call("lifelog.status").data!!.jsonObject["listening"]!!.jsonPrimitive.boolean)
        // Pausing is immediate from anywhere; resuming turns the microphone back on, so it follows the confirm policy.
        assertFalse(h.registry.needsConfirm(h.registry.ops["lifelog.pause"]!!, Origin.OTHER))
        assertTrue(h.call("lifelog.pause").ok)
        assertFalse(h.ui.listening.on)
        assertFalse(h.call("lifelog.status").data!!.jsonObject["listening"]!!.jsonPrimitive.boolean)
        assertTrue(h.registry.needsConfirm(h.registry.ops["lifelog.resume"]!!, Origin.OTHER))
        assertTrue(h.call("lifelog.resume", origin = Origin.GLASSES_TURN).ok)
        assertTrue(h.ui.listening.on)
    }

    @Test fun openOpsRespectTheLock(): Unit = ActionRegistryTest.Harness().use { h ->
        h.ui.locked = true
        listOf("docs.open" to mapOf("doc" to "Q4"), "calendar.open_day" to mapOf("day" to "today"), "report.open" to emptyMap(),
            "t3.open_thread" to mapOf("thread" to "1"), "teleprompter.start" to emptyMap<String, Any?>()).forEach { (op, args) ->
            assertEquals(op, Codes.UNAVAILABLE, h.call(op, args).code)
        }
        assertTrue(h.ui.top is Home)
        // A ringing call keeps screens out, and the op says so instead of claiming success.
        h.ui.locked = false
        (h.ui.data.calls as FakeCalls).incoming()
        assertEquals(Codes.UNAVAILABLE, h.call("docs.open", mapOf("doc" to "Q4")).code)
        assertFalse(h.ui.top is ListView && h.ui.top.id == "docs")
    }
}
