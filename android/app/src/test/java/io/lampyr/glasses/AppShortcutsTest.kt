package io.lampyr.glasses

import io.lampyr.glasses.audio.FakeDictation
import io.lampyr.glasses.data.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AppShortcutsTest {
    private class Timers: Scheduler {
        var now=0L
        data class Task(val at:Long,val run:()->Unit,var cancelled:Boolean=false)
        val tasks=mutableListOf<Task>()
        override fun after(delayMs:Long,block:()->Unit):Scheduled {
            val task=Task(now+delayMs,block);tasks+=task
            return object:Scheduled {override fun cancel(){task.cancelled=true}}
        }
        fun advance(ms:Long) {val end=now+ms;while(true){val task=tasks.filter {!it.cancelled&&it.at<=end}.minByOrNull {it.at}?:break;now=task.at;task.cancelled=true;task.run()};now=end}
    }
    private class Harness(val assignments:ShortcutAssignments=ShortcutAssignments(),registerTestAction:Boolean=true):AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val ui=Ui(FakeDataStore(RuntimeEnvironment.getApplication(),scope=scope),scope=scope,shortcutAssignments=assignments,nowS={0.0},nowMs={java.time.Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()},zone=java.time.ZoneId.of("America/Chicago"))
        val timers=Timers()
        val input=InputEngine(ui,FakeDictation(ui.data,scope),scheduler=timers)
        var calls=0
        init {if(registerTestAction)ui.shortcuts.register(AppShortcut("test.action","status","Test action",true,{calls++;"Done"}))}
        fun assign(id:String,action:String?="test.action")=ui.shortcuts.assign(FREE_GESTURES.first {it.id==id},action)
        fun tap(letter:String){input.press(letter);input.release(letter)}
        override fun close(){input.close();ui.close()}
    }
    @Test fun freeGesturesAndDefaultsAreExplicit()=Harness().use {h->
        assertEquals(listOf("a.hold","r.hold","star.hold","heart.hold","l2.tap","l2.hold"),FREE_GESTURES.map {it.id})
        assertFalse("act" in InputEngine.HOLDABLE)
        FREE_GESTURES.forEach {assertNull(h.ui.shortcuts.assigned(it))}
    }
    @Test fun unassignedTapsFireOnDownAndAssignedHoldDefersOnlyItsTap() {
        mapOf("a" to "np","r" to "notif","star" to "apps","heart" to "quick").forEach {(letter,_) -> Harness().use {h->
            val before=h.ui.top;h.input.press(letter);assertNotSame(letter,before,h.ui.top);h.input.release(letter)
            h.ui.goHome();h.assign("$letter.hold");h.input.press(letter);assertEquals("home",h.ui.top.id)
            h.timers.advance(449);assertEquals(0,h.calls);h.input.release(letter);assertNotEquals("home",h.ui.top.id);assertEquals(0,h.calls)
            h.ui.goHome();h.input.press(letter);h.timers.advance(450);assertEquals(1,h.calls);assertEquals("home",h.ui.top.id)
            h.input.press(letter);h.timers.advance(1000);h.input.release(letter);assertEquals(1,h.calls);assertEquals("home",h.ui.top.id)
            h.assign("$letter.hold",null);h.input.press(letter);assertNotEquals("home",h.ui.top.id)
        }}
    }
    @Test fun theMemoShortcutCannotStartARecordingDuringAPhoneCall()=Harness(registerTestAction=false).use {h->
        h.ui.shortcuts.register(VoiceMemo.shortcut(h.ui));h.assign("l2.tap","memo.toggle")
        (h.ui.data.calls as io.lampyr.glasses.calls.FakeCalls).incoming()
        h.tap("l2");assertFalse(h.ui.data.memos.snapshot.recording)
    }
    @Test fun joyOverridesOnlyTheL2TapSoAnAssignedL2HoldStillWorksThere()=Harness().use {h->
        h.assign("l2.tap");h.input.press("l2");assertEquals(1,h.calls);h.input.release("l2")
        h.assign("l2.hold");h.input.press("l2");h.timers.advance(450);h.input.release("l2");assertEquals(2,h.calls)
        h.ui.push(Joy(h.ui))
        // the assistant claims the tap (quiet wait). With a hold assigned, the tap is decided on release (INP-19).
        h.input.press("l2");assertFalse(h.ui.quiet);h.input.release("l2");assertTrue(h.ui.quiet);assertEquals(2,h.calls)
        // the assistant does not claim the hold: the assigned Hold L2 runs inside the assistant too and does not also toggle quiet.
        h.input.press("l2");h.timers.advance(450);h.input.release("l2");assertEquals(3,h.calls);assertTrue(h.ui.quiet)
        // Without a hold shortcut the assistant's tap acts on press again.
        h.assign("l2.hold",null);h.input.press("l2");assertFalse(h.ui.quiet);h.input.release("l2");assertEquals(3,h.calls)
        h.ui.goHome();h.input.press("l2");h.input.release("l2");assertEquals(4,h.calls)
    }
    @Test fun aScreenOverrideTakesItsButtonBeforeTheGlobalActionAndShortcuts()=Harness().use {h->
        h.assign("a.hold")
        var taps=0;var holds=0
        val claim=ButtonClaim("a","Test tap",tap=true,hold=true)
        h.ui.push(object:Screen(h.ui,"claims") {
            override fun overrides()=listOf(ButtonOverride(claim,onTap={taps++},onHold={holds++}))
            override fun draw(g:Gfx,t:Double){}
            override fun drawText(tg:TextGrid){}
        })
        h.input.press("a");h.input.release("a");assertEquals(1,taps);assertEquals("claims",h.ui.top.id) // not Now playing
        h.input.press("a");h.timers.advance(450);h.input.release("a");assertEquals(1,holds);assertEquals(1,taps);assertEquals(0,h.calls)
        assertNull("a claimed hold hides the global shortcut",h.ui.shortcuts.action("a",true))
        h.ui.goHome();h.input.press("a");h.timers.advance(450);h.input.release("a");assertEquals(1,h.calls) // global elsewhere
    }
    @Test fun backCanNeverBeOverriddenAndEveryAppDeclaresItsClaims()=Harness().use {h->
        listOf("b","l","r2","minus","y","x","plus","up").forEach { letter ->
            assertThrows(letter,IllegalArgumentException::class.java){ButtonClaim(letter,"x")}
        }
        assertFalse("b" in ButtonClaim.OVERRIDABLE)
        (APP_ENTRIES.map {it.id}+listOf("calls")).forEach {id->
            val view=h.ui.app(id)
            assertTrue(id,view.overrides().none {it.letter=="b"})
            assertEquals(id,APP_BUTTON_CLAIMS[id].orEmpty().toSet(),view.overrides().map {it.claim}.toSet())
        }
        // B still goes back and hold B still goes Home from screens that override buttons.
        listOf(Joy(h.ui)).forEach {screen->
            h.ui.goHome();h.ui.push(Apps(h.ui));h.ui.push(screen)
            h.tap("b");assertEquals("apps",h.ui.top.id)
            h.ui.push(screen);h.input.press("b");h.timers.advance(450);h.input.release("b");assertEquals("home",h.ui.top.id)
        }
    }
    @Test fun shortcutsListOnlyButtonsTheControllerHasAndNameTheAppsThatKeepAGesture()=Harness().use {h->
        // A learned 8BitDo Micro map: no star (it sends nothing in keyboard mode).
        val learned=setOf("up","down","left","right","y","b","x","a","l","l2","r","r2","minus","plus","heart")
        assertEquals(FREE_GESTURES,h.ui.shortcuts.gestures())
        h.ui.shortcuts.controllerLetters={learned}
        assertEquals(listOf("a.hold","r.hold","heart.hold","l2.tap","l2.hold"),h.ui.shortcuts.gestures().map {it.id})
        assertEquals(listOf("Hermes"),h.ui.shortcuts.overriddenIn(FREE_GESTURES.first {it.id=="l2.tap"}).map {it.label})
        assertTrue(h.ui.shortcuts.overriddenIn(FREE_GESTURES.first {it.id=="l2.hold"}).isEmpty())
        assertTrue(h.ui.shortcuts.overriddenIn(FREE_GESTURES.first()).isEmpty())
        assertEquals("None · not in Hermes",h.ui.shortcuts.summary(FREE_GESTURES.first {it.id=="l2.tap"}))
        val rows=Shortcuts(h.ui).rows()
        assertEquals(listOf("Hold A","Hold R","Hold People","Tap L2","Hold L2"),rows.map {it.label})
        assertTrue(rows.none {"not ${io.lampyr.glasses.settings.Names.assistant}" in it.label || "n/a" in (it.sub ?: "") || "applicable" in (it.sub ?: "")})
        // A hidden gesture's saved assignment is kept, and the row index maps to the filtered list.
        val shortcuts=Shortcuts(h.ui);h.ui.push(shortcuts);shortcuts.cur=2;shortcuts.key("sel")
        assertEquals("heart.hold",(h.ui.top as ShortcutActions).gesture.id)
    }
    @Test fun learnedControllerLettersComeFromTheSavedKeyMap() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("controller",0)
        prefs.edit().clear().commit();assertNull(KeyMap.learnedLetters(context))
        prefs.edit().putString("keyMap","""{"19":"up","66":"y","62":"l2","33":"heart"}""").commit()
        assertEquals(setOf("up","y","l2","heart"),KeyMap.learnedLetters(context))
        prefs.edit().putString("keyMap","not json").commit();assertNull(KeyMap.learnedLetters(context))
        prefs.edit().clear().commit()
    }
    @Test fun savedL2HoldMemoSurvivesTheOverrideRefactorEverywhere() {
        // Requirement 15.4 migration: a saved app-shortcuts.xml with l2.hold = memo.toggle.
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("shortcuts-l2-migration",0)
        prefs.edit().clear().putString("l2.hold","memo.toggle").commit()
        Harness(ShortcutAssignments(prefs),registerTestAction=false).use {h->
            h.ui.shortcuts.controllerLetters={setOf("up","down","left","right","y","b","x","a","l","l2","r","r2","minus","plus","heart")}
            val hold=h.ui.shortcuts.gestures().first {it.id=="l2.hold"}
            assertEquals("Memo: start/stop",h.ui.shortcuts.assigned(hold)?.label)
            for(screen in listOf<() -> View>({Apps(h.ui)},{Joy(h.ui)},{Beeper(h.ui)})) {
                h.ui.goHome();h.ui.push(screen())
                h.input.press("l2");h.timers.advance(450);h.input.release("l2");assertTrue(h.ui.top.id,h.ui.data.memos.snapshot.recording)
                h.input.press("l2");h.timers.advance(450);h.input.release("l2");assertFalse(h.ui.top.id,h.ui.data.memos.snapshot.recording)
            }
            h.ui.goHome();h.ui.locked=true
            h.input.press("l2");h.timers.advance(450);h.input.release("l2");assertFalse(h.ui.data.memos.snapshot.recording)
            assertEquals("memo.toggle",prefs.getString("l2.hold",null))
        }
    }
    @Test fun lockedCaptureAndConsumedTextEntryCannotRunShortcuts()=Harness().use {h->
        h.assign("a.hold");h.ui.locked=true;h.input.press("a");h.timers.advance(500);h.input.release("a");assertEquals(0,h.calls)
        h.ui.locked=false
        h.ui.push(DictationScreen(h.ui,TalkTarget("Text",send={ActionResult()})))
        h.input.press("a");h.timers.advance(500);h.input.release("a");assertEquals(0,h.calls)
        h.ui.goHome();h.input.press("l");h.input.press("a");h.timers.advance(500);h.input.release("a");h.input.release("l");assertEquals(0,h.calls)
        h.ui.push(object:Screen(h.ui,"entry") {
            override val consumesShortcuts=true
            override fun draw(g:Gfx,t:Double){}
            override fun drawText(tg:TextGrid){}
        })
        h.input.press("a");h.timers.advance(500);h.input.release("a");assertEquals(0,h.calls)
    }
    @Test fun pendingHoldCannotFireAfterCaptureStartsOrLock()=Harness().use {h->
        h.assign("a.hold");h.input.press("a");h.ui.push(DictationScreen(h.ui,TalkTarget("Text",send={ActionResult()})))
        h.timers.advance(450);h.input.release("a");assertEquals(0,h.calls)
        h.ui.goHome();h.input.press("a");h.ui.locked=true;h.timers.advance(450);h.input.release("a");assertEquals(0,h.calls)
    }
    @Test fun preferencesPersistAndUnknownOrRemovedActionsResolveToNone() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("shortcuts-test",0);prefs.edit().clear().commit()
        Harness(ShortcutAssignments(prefs)).use {it.assign("a.hold","memo.toggle")}
        Harness(ShortcutAssignments(prefs)).use {h->
            val a=FREE_GESTURES.first();assertEquals("memo.toggle",h.ui.shortcuts.assigned(a)?.id)
            h.assignments.set(a.id,"removed.action");assertNull(h.ui.shortcuts.assigned(a))
            h.assign(a.id,"memo.toggle");runBlocking {h.ui.data.settings.update(h.ui.data.settings.snapshot.copy(appOrder=listOf("status")))}
            assertNull(h.ui.shortcuts.assigned(a))
        }
    }
    @Test fun shoulderSafetyIsEnforcedForPickerPersistedIdsAndAssignments()=Harness().use {h->
        h.ui.shortcuts.register(AppShortcut("test.unsafe","status","Unsafe test",false,{"Done"}))
        FREE_GESTURES.forEach {gesture->
            val ids=h.ui.shortcuts.actions(gesture).map {it.id}
            assertEquals(!gesture.shoulder,"test.unsafe" in ids)
            if(gesture.shoulder) {
                h.assignments.set(gesture.id,"test.unsafe");assertNull(h.ui.shortcuts.assigned(gesture))
                assertThrows(IllegalArgumentException::class.java){h.ui.shortcuts.assign(gesture,"test.unsafe")}
            }
        }
    }
    @Test fun memoActionUsesExistingToggleAndStateLabel()=Harness().use {h->
        h.assign("a.hold","memo.toggle");val action=h.ui.shortcuts.action("a",true)!!
        assertEquals("Start voice memo",action.state!!())
        h.input.press("a");h.timers.advance(450);h.input.release("a");assertTrue(h.ui.data.memos.snapshot.recording)
        assertEquals("Stop voice memo",action.state!!())
        h.input.press("a");h.timers.advance(450);h.input.release("a");assertFalse(h.ui.data.memos.snapshot.recording)
    }
    @Test fun theSavedL2HoldMemoShortcutStillStartsAndStopsAMemo() {
        // Requirement 15.4: a saved l2.hold = memo.toggle must keep toggling a memo.
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("shortcuts-l2-memo",0)
        prefs.edit().clear().putString("l2.hold","memo.toggle").commit()
        Harness(ShortcutAssignments(prefs),registerTestAction=false).use {h->
            assertEquals("memo.toggle",h.ui.shortcuts.assigned(FREE_GESTURES.first {it.id=="l2.hold"})?.id)
            val before=h.ui.data.memos.snapshot.list.size
            h.input.press("l2");h.timers.advance(450);h.input.release("l2");assertTrue(h.ui.data.memos.snapshot.recording)
            h.input.press("l2");h.timers.advance(450);h.input.release("l2");assertFalse(h.ui.data.memos.snapshot.recording)
            assertEquals(before+1,h.ui.data.memos.snapshot.list.size)
        }
    }
    @Test fun memoShortcutReportsFailureInsteadOfSuccess() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val base=FakeDataStore(RuntimeEnvironment.getApplication(),scope=scope)
        val data=object:DataStore by base {
            override val memos=object:MemosRepository by base.memos {
                override suspend fun toggle()=ActionResult.failure("Microphone unavailable")
            }
        }
        val ui=Ui(data,scope=scope).apply {display=Display.Text()}
        try {
            ui.shortcuts.assign(FREE_GESTURES.first(),"memo.toggle");ui.shortcuts.run("a",true)
            assertFalse(ui.data.memos.snapshot.recording)
            assertTrue(ui.render().text.lines().any {"Microphone unavailable" in it})
        } finally {ui.close()}
    }
    @Test fun phonePickerUsesTheSameAssignmentsAndShoulderFilter() {
        val context=RuntimeEnvironment.getApplication()
        io.lampyr.glasses.settings.AppSettings(context).live=false
        val prefs=context.getSharedPreferences("app-shortcuts",0)
        prefs.edit().clear().putString("a.hold","removed.action").commit()
        val owner=Robolectric.buildService(io.lampyr.glasses.service.GlassesService::class.java).create()
        val activity=Robolectric.buildActivity(io.lampyr.glasses.phone.ShortcutsActivity::class.java).create()
        fun buttons(view:android.view.View):List<android.widget.TextView> =
            (if(view is android.widget.TextView && view.isClickable)listOf(view)else emptyList()) +
                if(view is android.view.ViewGroup)(0 until view.childCount).flatMap {buttons(view.getChildAt(it))}else emptyList()
        try {
            val root=activity.get().findViewById<android.view.View>(android.R.id.content)
            buttons(root).first {it.text.toString()=="Hold A: None"}.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(listOf("None","Memo: start/stop"),(0 until dialog.listView.adapter.count).map {dialog.listView.adapter.getItem(it).toString()})
            dialog.listView.performItemClick(null,1,1)
            assertEquals("memo.toggle",prefs.getString("a.hold",null))
            buttons(root).first {it.text.toString().startsWith("Hold R:")}.performClick()
            val shoulder=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(listOf("None","Memo: start/stop"),(0 until shoulder.listView.adapter.count).map {shoulder.listView.adapter.getItem(it).toString()})
            shoulder.dismiss();owner.get().reloadData()
            assertEquals("memo.toggle",owner.get().ui.shortcuts.assigned(FREE_GESTURES.first())?.id)
        } finally {activity.destroy();owner.destroy()}
    }
    @Test fun shortcutSettingsMatchBothTextGrids()=Harness(registerTestAction=false).use {h->
        listOf(26 to 7,20 to 5).forEach {(cols,rows)->
            listOf("settings" to Settings(h.ui),"shortcuts" to Shortcuts(h.ui),
                "shortcut-actions" to ShortcutActions(h.ui,FREE_GESTURES.first()),
                "shortcuts-last" to Shortcuts(h.ui).apply {cur=5},
                "shortcut-shoulder" to ShortcutActions(h.ui,FREE_GESTURES[1])).forEach {(name,view)->
                val grid=TextGrid(cols,rows,false);view.drawText(grid);assertFalse(grid.overflowed)
                val actual=grid.lines().joinToString("\n")+"\n"
                val file=java.io.File("src/test/resources/textgolden/$name-$cols.txt")
                if(System.getenv("UPDATE_TEXT_GOLDENS")=="1")file.writeText(actual)
                assertEquals(file.readText().replace("\r\n","\n"),actual)
            }
        }
    }
    @Test fun settingsSelectionPersistsAndNoneClears()=Harness().use {h->
        h.ui.push(Settings(h.ui));val settings=h.ui.top as Settings;settings.cur=settings.rows().indexOfFirst {it.label=="Shortcuts"};settings.key("sel")
        val shortcuts=h.ui.top as Shortcuts;shortcuts.key("sel");val actions=h.ui.top as ShortcutActions
        actions.cur=actions.rows().indexOfFirst {it.label=="Memo: start/stop"};actions.key("sel")
        assertEquals("memo.toggle",h.ui.shortcuts.assigned(FREE_GESTURES.first())?.id)
        shortcuts.key("sel");(h.ui.top as ShortcutActions).key("sel");assertNull(h.ui.shortcuts.assigned(FREE_GESTURES.first()))
    }
}
