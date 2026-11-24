package io.lampyr.glasses.ambient

import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PeopleScreenTest {
    @Test fun personMuteHasSeparateActionAndConfirmationOnBothGrids() {
        for(mode in listOf(Display.Text(26,7),Display.Text(20,5))) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val store=FakeDataStore(RuntimeEnvironment.getApplication(),scope=scope)
            val ui=Ui(store,scope=scope).apply { display=mode }
            val person=store.people.snapshot.recent.first()
            try {
                ui.push(PersonCard(ui,person.id))
                assertTrue(ui.top.key("act")); assertEquals("personact",ui.top.id)
                fun text()=TextGrid(mode.cols,mode.rows,uppercase=false).also { ui.top.drawText(it) }.let { assertFalse(it.overflowed);it.lines().joinToString("\n") }
                assertTrue(text().contains("Person actions"))
                ui.top.key("sel");assertEquals("personconfirm",ui.top.id)
                assertFalse(store.people.snapshot.recent.first().muted)
                ui.top.key("down");ui.top.key("sel") // cancel
                assertFalse(store.people.snapshot.recent.first().muted)
                ui.top.key("sel");ui.top.key("sel")
                assertTrue(store.people.snapshot.recent.first().muted)
                ui.top.key("act");text();assertEquals("Count this person again",(ui.top as ListView).rows().first().label)
                ui.top.key("sel");ui.top.key("sel")
                assertFalse(store.people.snapshot.recent.first().muted)
            } finally { ui.close();scope.cancel() }
        }
    }
    @Test fun longBlurbIsPagedWithoutLosingNotesOnBothGrids() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val base=FakeDataStore(RuntimeEnvironment.getApplication(),scope=scope)
        val p=Person("synthetic","Synthetic person","41,-87","2026-11-12T12:00:00Z","Synthetic notes ".repeat(30))
        val data=object:DataStore by base { override val people=object:PeopleRepository {
            override val snapshot=PeopleSnapshot(listOf(p),listOf(p))
            override suspend fun refresh()=ActionResult()
            override suspend fun remind(personId:String,text:String)=ActionResult()
        } }
        val ui=Ui(data,scope=scope)
        try { for(mode in listOf(Display.Text(26,7),Display.Text(20,5))) {
            ui.display=mode;val card=PersonCard(ui,p.id)
            assertTrue(card.textPages().size>1)
            val grid=TextGrid(mode.cols,mode.rows,uppercase=false);card.drawText(grid)
            assertFalse(grid.overflowed);assertTrue(grid.lines().joinToString().contains("Synthetic"))
            card.key("down");assertEquals(1,card.page)
        } } finally { ui.close();scope.cancel() }
    }
    @Test fun mutedPeopleHaveBottomRowCardsAndUndoOnRasterAndBothTextGrids() {
        for(mode in listOf(Display.Bitmap,Display.Text(26,7),Display.Text(20,5))) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val store=FakeDataStore(RuntimeEnvironment.getApplication(),scope=scope)
            val ui=Ui(store,scope=scope).apply { display=mode }
            val person=store.people.snapshot.recent.first()
            try {
                runBlocking { store.people.mute(person.id,true) }
                val people=People(ui);ui.push(people)
                assertFalse(people.rows().any { it.label==person.name })
                assertEquals("Muted (1)",people.rows().last().label)
                people.cur=people.rows().lastIndex;people.key("sel")
                assertEquals("people-muted",ui.top.id)
                val muted=ui.top as ListView;assertEquals(person.name,muted.rows().single().label)
                if(mode is Display.Text) {
                    val grid=TextGrid(mode.cols,mode.rows,uppercase=false);muted.drawText(grid);assertFalse(grid.overflowed)
                    assertTrue(grid.lines().joinToString().contains("Muted people"))
                } else assertEquals(540,ui.render().bitmap.width)
                muted.key("sel");assertEquals("card",ui.top.id)
                ui.top.key("act");assertEquals("Count this person again",(ui.top as ListView).rows().single().label)
                ui.top.key("sel");ui.top.key("sel")
                assertFalse(store.people.snapshot.recent.first().muted)
                ui.pop();ui.pop()
                assertEquals("Muted (0)",(ui.top as ListView).rows().last().label)
            } finally { ui.close();scope.cancel() }
        }
    }

    @Test fun peopleAndMutedRasterGoldens() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val store=FakeDataStore(RuntimeEnvironment.getApplication(),scope=scope)
        val ui=Ui(store,scope=scope,nowS={0.0},nowMs={1_800_000_000_000L},zone=java.time.ZoneId.of("UTC"))
        try {
            runBlocking { store.people.mute(store.people.snapshot.recent.first().id,true) }
            val people=People(ui).apply { cur=rows().lastIndex }
            for((name,screen) in listOf("people-muted-row" to people,"people-muted" to MutedPeople(ui))) {
                ui.replaceTop(screen)
                val bitmap=ui.render().bitmap
                val file=java.io.File("src/test/resources/golden/$name.png")
                if(System.getenv("UPDATE_PEOPLE_GOLDENS")=="1") {
                    file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                }
                val expected=android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                assertNotNull("Missing $name golden",expected)
                val actual=IntArray(bitmap.width*bitmap.height); val reference=IntArray(actual.size)
                bitmap.getPixels(actual,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                expected.getPixels(reference,0,expected.width,0,0,expected.width,expected.height)
                assertArrayEquals(name,reference,actual)
            }
        } finally { ui.close();scope.cancel() }
    }

}
