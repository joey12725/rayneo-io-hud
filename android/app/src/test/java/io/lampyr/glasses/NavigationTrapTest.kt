package io.lampyr.glasses

import io.lampyr.glasses.data.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * No app root is a left/right trap: after any mix of ◀▶▲▼ presses on the bitmap and the 26x7 / 20x5 text displays,
 * B (handled like InputEngine: the screen may consume it, otherwise the stack pops) still leaves the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationTrapTest {
    private val fixedMs = Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()

    private fun ui(display: Display): Ui {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return Ui(FakeDataStore(RuntimeEnvironment.getApplication(), { 100.0 }, { fixedMs }, scope), scope = scope, nowS = { 100.0 },
            nowMs = { fixedMs }, zone = ZoneId.of("America/Chicago")).apply { this.display = display }
    }

    @Test fun bAlwaysLeavesEveryAppAfterArrowPresses() {
        for (display in listOf(Display.Bitmap, Display.Text(), Display.Text(20, 5))) {
            for (entry in APP_ENTRIES) {
                val ui = ui(display)
                try {
                    val base = ui.stack.size
                    val root = ui.app(entry.id)
                    ui.push(root)
                    for (k in listOf("right", "right", "down", "down", "left", "down", "right", "left", "left")) {
                        if (ui.top === root) root.key(k)
                    }
                    assertSame("${entry.id} $display: arrows never leave the app root", root, ui.top)
                    if (!ui.top.key("back")) ui.pop()
                    assertFalse("${entry.id} $display: one B leaves the app", root in ui.stack)
                    assertEquals(base, ui.stack.size)
                } finally { ui.close() }
            }
        }
    }

    @Test fun phoneArrowsAlwaysChangeTabOnBothDisplays() {
        for (display in listOf(Display.Bitmap, Display.Text(), Display.Text(20, 5))) {
            val ui = ui(display)
            try {
                val root = PhoneRoot(ui, PhoneTab.Contacts); ui.push(root)
                repeat(4) { root.key("down") }
                root.key("right"); assertEquals(PhoneTab.Favorites, root.tab)
                root.key("left"); assertEquals(PhoneTab.Contacts, root.tab)
                root.key("left"); assertEquals(PhoneTab.Recents, root.tab)
                if (!ui.top.key("back")) ui.pop()
                assertFalse(root in ui.stack)
            } finally { ui.close() }
        }
    }
}
