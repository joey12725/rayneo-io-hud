package io.lampyr.glasses

import android.graphics.Bitmap
import android.graphics.Canvas
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapPunchListTest {
    private fun draw(view: View): Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also {
        it.eraseColor(BLACK)
        view.draw(Gfx(Canvas(it)), 0.0)
    }

    @Test fun approvalRequiresRenderedLastPageRelocksOnChangeAndAlwaysAllowsDeny() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { 1_000_000L }, scope)
        val key = ThreadKey("vps", "2")
        var request = Request("first", "approval", approval = Approval("Run command",
            "echo " + "part ".repeat(55), "A reason " + "detail ".repeat(30)))
        val decisions = mutableListOf<String?>()
        val t3 = object : T3Repository by data.t3 {
            override fun detail(machine: String, id: String) = data.t3.detail(machine, id)!!.copy(request = request)
            override suspend fun respond(machine: String, id: String, requestId: String, decision: String?, answers: Map<String, List<String>>): ActionResult {
                decisions += decision
                return ActionResult()
            }
        }
        val ui = Ui(object : DataStore by data { override val t3 = t3 }, scope = scope, nowS = { 0.0 })
        try {
            val view = T3Approval(ui, key)
            view.cur = 2
            view.key("sel") // Deny even before the first frame is rendered.
            assertEquals(listOf("decline"), decisions)
            view.cur = 0
            val first = draw(view)
            assertEquals(0, view.top)
            assertEquals(GREEN, first.getPixel(W - 12, TOP + 78)) // Scroll indicator.
            assertTrue(BOTTOM - 24 - view.y0 >= 2 * 34) // Two full choices, unchanged type size.
            view.key("sel")
            assertEquals(1, decisions.size)
            repeat(100) { view.key("right") }
            assertTrue(view.bitmapPage > 0)
            view.key("sel") // Merely navigating to the last page is insufficient.
            assertEquals(1, decisions.size)
            draw(view)
            view.key("sel")
            assertEquals("accept", decisions.last())
            view.key("left"); draw(view)
            view.cur = 1; view.key("sel")
            assertEquals("acceptForSession", decisions.last())
            request = request.copy(id = "changed", approval = request.approval!!.copy(reason = "Changed reason"))
            view.cur = 0; view.key("sel")
            assertEquals(3, decisions.size)
            assertEquals(0, view.bitmapPage)
            view.cur = 2; draw(view)
            assertEquals(1, view.top) // Deny and the preceding choice are both in view.
            view.key("sel")
            assertEquals("decline", decisions.last())
        } finally { ui.close() }
    }

    @Test fun railWordsHaveShortLabelsAndBitmapEllipsesNeverFollowSeparators() {
        assertEquals("Books", railCaption("Audiobooks"))
        assertEquals("Auto scroll", railCaption("Auto-scroll"))
        assertEquals("REC", railCaption("RECORDING"))
        for (caption in listOf("Books", "Glasses link OK", "Oura · this morning", "165.4 lb",
            "6:41 AM today", "Auto-scroll", "RECORDING", "Spotify Connect", "Personal SIM")) {
            val short = railCaption(caption)
            assertTrue(short.split(' ').all { tw(it, "s") <= RAIL - 4 })
            assertTrue(layout(short, RAIL - 4, "s").all { lineWidth(it, "s") <= RAIL - 4 })
        }
        val text = "rayneo-glasses · Claude Code"
        assertEquals("rayneo-glasses", fit(text, tw("rayneo-glasses · …")))
        assertEquals("rayneo-glasses", clampLines(text, tw("rayneo-glasses · …"), 1).single().joinToString("") {
            (if (it.gap) " " else "") + it.w.orEmpty()
        })
    }
}
