package io.lampyr.glasses

import android.content.Context
import android.graphics.Bitmap
import io.lampyr.glasses.data.FakeDataStore
import io.lampyr.glasses.glasses.MemoryStockSettings
import io.lampyr.glasses.render.Font5
import io.lampyr.glasses.render.Fonts
import io.lampyr.glasses.render.TextCase
import io.lampyr.glasses.render.TextGrid
import io.lampyr.glasses.render.caseText
import io.lampyr.glasses.render.fit
import io.lampyr.glasses.render.norm5
import io.lampyr.glasses.render.tw
import io.lampyr.glasses.render.tx
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Display preferences: text case, display timeout choices, look-up modes and the locked look-up rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisplayPrefsTest {
    @After fun resetCase() { Fonts.textCase = TextCase.CAPS }

    // ---------- Preferences and migration ----------
    @Test fun defaultsKeepTodaysBehaviour() {
        val d = PowerPrefs()
        assertEquals(LookUp.ON, d.lookUp)
        assertFalse("Conservative: looking up does not wake a locked headset", d.lookUpWhenLocked)
        assertEquals(TextCase.CAPS, d.textCase)
        assertTrue(d.lookUpWake)
        assertEquals(d, PowerPrefs.read(emptyMap<String, Any>()))
    }

    @Test fun legacyLookUpBooleanMigrates() {
        assertEquals(LookUp.ON, PowerPrefs.read(mapOf(PowerPrefs.LEGACY_LOOK_UP_WAKE to true)).lookUp)
        assertEquals(LookUp.NOTHING, PowerPrefs.read(mapOf(PowerPrefs.LEGACY_LOOK_UP_WAKE to false)).lookUp)
        assertEquals(LookUp.NOTHING, PowerPrefs.read(mapOf(PowerPrefs.LEGACY_LOOK_UP_WAKE to "off")).lookUp)
        assertEquals(LookUp.ON, PowerPrefs.read(mapOf(PowerPrefs.LEGACY_LOOK_UP_WAKE to 1)).lookUp)
        assertEquals("The new key wins over the old boolean", LookUp.TOGGLE,
            PowerPrefs.read(mapOf(PowerPrefs.LEGACY_LOOK_UP_WAKE to false, PowerPrefs.LOOK_UP to "toggle")).lookUp)
        assertEquals("An invalid new value falls back to the migrated one", LookUp.NOTHING,
            PowerPrefs.read(mapOf(PowerPrefs.LEGACY_LOOK_UP_WAKE to false, PowerPrefs.LOOK_UP to "sideways")).lookUp)
        for (mode in LookUp.entries) assertEquals(mode, PowerPrefs.read(mapOf(PowerPrefs.LOOK_UP to mode.name.lowercase())).lookUp)
        assertTrue(PowerPrefs.read(mapOf(PowerPrefs.LOOK_UP_WHEN_LOCKED to "on")).lookUpWhenLocked)
        assertEquals(TextCase.NORMAL, PowerPrefs.read(mapOf(PowerPrefs.TEXT_CASE to "normal")).textCase)
        assertEquals(TextCase.CAPS, PowerPrefs.read(mapOf(PowerPrefs.TEXT_CASE to "mixed")).textCase)
    }

    @Test fun sharedStoreMigratesOnFirstWriteAndListensToTextCase() {
        val shared = RuntimeEnvironment.getApplication().getSharedPreferences("display-prefs-test", Context.MODE_PRIVATE)
        shared.edit().clear().putBoolean(PowerPrefs.LEGACY_LOOK_UP_WAKE, false).commit()
        val store = SharedPowerStore(shared)
        assertEquals(LookUp.NOTHING, store.prefs.lookUp)
        var heard = 0
        val stop = store.listen { heard++ }
        store.update(store.prefs.copy(textCase = TextCase.NORMAL, lookUpWhenLocked = true))
        assertFalse("The old boolean is removed once the new key is written", shared.contains(PowerPrefs.LEGACY_LOOK_UP_WAKE))
        assertEquals("NOTHING", shared.getString(PowerPrefs.LOOK_UP, null))
        assertEquals(PowerPrefs(lookUp = LookUp.NOTHING, lookUpWhenLocked = true, textCase = TextCase.NORMAL), store.prefs)
        val before = heard
        shared.edit().putString(PowerPrefs.TEXT_CASE, "CAPS").commit()
        assertTrue("display.textCase changes reach power listeners", heard > before)
        stop()
    }

    @Test fun displayTimeoutChoicesIncludeFinerAndLongerSteps() {
        assertEquals(listOf(5_000L, 10_000L, 12_000L, 15_000L, 20_000L, 30_000L, 60_000L, 0L), PowerPrefs.IDLE_OPTIONS)
        assertEquals(60_000L, PowerPrefs.read(mapOf(PowerPrefs.IDLE to 60_000)).idleMs)
        assertEquals(15_000L, PowerPrefs.read(mapOf(PowerPrefs.IDLE to "15000")).idleMs)
    }

    // ---------- Look-up modes ----------
    private class Fixture(prefs: PowerPrefs) {
        var seconds = 100.0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { seconds }, { 1_763_000_000_000L }, scope)
        val power = MemoryPowerStore(prefs)
        val stock = MemoryStockSettings()
        val ui = Ui(data, scope = scope, nowS = { seconds }, nowMs = { 1_763_000_000_000L }, powerStore = power, glassesSettings = stock)
        fun close() { ui.close(); scope.cancel() }
    }

    private fun fixture(prefs: PowerPrefs, block: Fixture.() -> Unit) { val f = Fixture(prefs); try { f.block() } finally { f.close() } }
    private fun prefs(mode: LookUp, locked: Boolean = false) = PowerPrefs(idleMs = 5_000, lookUp = mode, lookUpWhenLocked = locked)

    @Test fun onTurnsTheLensOnAndKeepsItOn() = fixture(prefs(LookUp.ON)) {
        assertFalse(ui.awake)
        assertEquals("on", ui.onLookUp()); assertTrue(ui.awake)
        assertEquals("on", ui.onLookUp()); assertTrue(ui.awake)
        assertEquals("lookup", ui.power.lastWakeReason)
    }

    @Test fun offTurnsTheLensOff() = fixture(prefs(LookUp.OFF)) {
        ui.wake("button"); assertTrue(ui.awake)
        assertEquals("off", ui.onLookUp()); assertFalse(ui.awake)
        assertEquals("off", ui.onLookUp()); assertFalse("OFF never lights a dark lens", ui.awake)
    }

    @Test fun toggleFlipsTheLens() = fixture(prefs(LookUp.TOGGLE)) {
        assertEquals("on", ui.onLookUp()); assertTrue(ui.awake)
        assertEquals("off", ui.onLookUp()); assertFalse(ui.awake)
        assertEquals("on", ui.onLookUp()); assertTrue(ui.awake)
    }

    @Test fun nothingIgnoresLookUp() = fixture(prefs(LookUp.NOTHING)) {
        assertEquals("ignored", ui.onLookUp()); assertFalse(ui.awake)
        ui.wake("button")
        assertEquals("ignored", ui.onLookUp()); assertTrue(ui.awake)
    }

    @Test fun lockedHeadsetIgnoresLookUpByDefaultInEveryMode() {
        for (mode in LookUp.entries) fixture(prefs(mode)) {
            ui.locked = true
            assertEquals(mode.name, "ignored", ui.onLookUp())
            assertTrue(mode.name, ui.locked); assertFalse(mode.name, ui.awake)
        }
    }

    @Test fun lookUpWhenLockedIsHonouredByEveryMode() {
        val expected = mapOf(LookUp.ON to "on", LookUp.TOGGLE to "on", LookUp.OFF to "ignored", LookUp.NOTHING to "ignored")
        for ((mode, result) in expected) fixture(prefs(mode, locked = true)) {
            ui.locked = true
            assertEquals(mode.name, result, ui.onLookUp())
            assertEquals(mode.name, result == "ignored", ui.locked)
            assertEquals(mode.name, result == "on", ui.awake)
        }
    }

    @Test fun wearerSignalsRouteLookUpAndCrown() {
        fixture(prefs(LookUp.OFF)) {
            ui.wake("button")
            assertEquals("Stock head-up while parked arrives as screen_status", "off", ui.onWearerSignal("screen_status")); assertFalse(ui.awake)
            assertEquals("The crown always wakes", "on", ui.onWearerSignal("crown")); assertTrue(ui.awake)
        }
        fixture(prefs(LookUp.NOTHING)) {
            assertEquals("With stock head-up off, screen_status can only be the crown", "on", ui.onWearerSignal("screen_status")); assertTrue(ui.awake)
            ui.sleep()
            assertEquals("ignored", ui.onWearerSignal("lookup")); assertFalse(ui.awake)
        }
        fixture(prefs(LookUp.ON)) {
            ui.locked = true
            assertEquals("ignored", ui.onWearerSignal("screen_status")); assertTrue(ui.locked)
        }
    }

    // ---------- Settings screens ----------
    private fun lines(view: View, cols: Int, rows: Int) = TextGrid(cols, rows, false).also { view.drawText(it); assertFalse(it.overflowed) }.lines()

    @Test fun displaySleepScreenOffersTimeoutLookUpLockedAndTextCase() = fixture(PowerPrefs()) {
        val view = PowerSettings(ui); ui.push(view)
        val labels = view.rows().map { it.label }
        assertEquals("Display timeout", labels.first())
        assertTrue(labels.containsAll(listOf("Look up", "Look up when locked", "Text case")))
        view.cur = 0; view.key("sel"); assertEquals(20_000L, power.prefs.idleMs)
        view.cur = labels.indexOf("Look up")
        val seen = (1..4).map { view.key("sel"); power.prefs.lookUp }
        assertEquals(listOf(LookUp.OFF, LookUp.TOGGLE, LookUp.NOTHING, LookUp.ON), seen)
        assertTrue("Back to ON re-enables stock head-up", stock.current!!.headUp)
        view.cur = labels.indexOf("Look up when locked"); view.key("sel"); assertTrue(power.prefs.lookUpWhenLocked)
        view.cur = labels.indexOf("Text case"); view.key("sel")
        assertEquals(TextCase.NORMAL, power.prefs.textCase)
        assertEquals("The Ui keeps the renderer in step", TextCase.NORMAL, Fonts.textCase)
        for (mode in LookUp.entries) {
            power.update(power.prefs.copy(lookUp = mode))
            for (cur in view.rows().indices) {
                view.cur = cur
                lines(view, 26, 7); lines(view, 20, 5)
            }
        }
        view.cur = labels.indexOf("Text case"); view.key("sel"); assertEquals(TextCase.CAPS, power.prefs.textCase); assertEquals(TextCase.CAPS, Fonts.textCase)
    }

    // ---------- Text case rendering ----------
    @Test fun everyLetterHasALowercaseGlyphInsideTheSevenRowCell() {
        for (c in 'a'..'z') {
            val rows = Font5.glyphs[c.toString()]
            assertNotNull("missing $c", rows)
            assertEquals(7, rows!!.size)
            assertTrue(rows.all { it.length == 5 && it.all { b -> b == '0' || b == '1' } })
            assertNotEquals("$c must differ from its capital", Font5.glyphs.getValue(c.uppercase()), rows)
        }
    }

    @Test fun simulatorFontMatchesTheNativeFont() {
        val html = listOf("../../simulator/index.html", "../simulator/index.html", "simulator/index.html").map(::File).firstOrNull { it.isFile }
        org.junit.Assume.assumeNotNull(html)
        val line = html!!.readLines().first { it.startsWith("const FONT5=") }
        val sim = Regex("'([A-Za-z0-9])':\\[([^\\]]*)]").findAll(line).associate { m ->
            m.groupValues[1] to m.groupValues[2].split(',').map { it.trim('\'') }
        }
        for (c in ('a'..'z') + ('A'..'Z') + ('0'..'9')) assertEquals("glyph $c", Font5.glyphs.getValue(c.toString()), sim[c.toString()])
        // Every glyph, symbols included: a character the simulator lacks would draw as a fallback in the goldens.
        val all = Regex("""(?:'((?:\\.|[^'\\])+)'|"([^"]+)"):\[([^\]]*)]""").findAll(line.substringAfter("{")).associate { m ->
            (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace("\\\\", "\\") to m.groupValues[3].split(',').map { it.trim('\'') }
        }
        assertEquals(Font5.glyphs.keys, all.keys)
        for ((c, rows) in Font5.glyphs) assertEquals("glyph $c", rows, all[c])
    }

    @Test fun capsIsTheDefaultAndNormalKeepsLowercase() {
        assertEquals("HELLO, CAFE...", norm5("Hello, café…"))
        assertEquals("WEDNESDAY", caseText("Wednesday"))
        Fonts.textCase = TextCase.NORMAL
        assertEquals("Hello, cafe...", norm5("Hello, café…"))
        assertEquals("Wednesday", caseText("Wednesday"))
        assertEquals("Characters without a lowercase glyph fall back to capitals", "SS", norm5("ß"))
        assertEquals("Case never changes the measured width", tw("Hello world", "m"), tw("HELLO WORLD", "m"))
        assertEquals(fit("Hello world again", 100, "m").length, fit("HELLO WORLD AGAIN", 100, "m").length)
    }

    private fun Bitmap.litRows(): Pair<Int, Int> {
        var top = height; var bottom = -1
        for (y in 0 until height) for (x in 0 until width) if ((getPixel(x, y) and 0x00ffffff) != 0) { top = minOf(top, y); bottom = maxOf(bottom, y) }
        return top to bottom
    }

    @Test fun lowercaseNeverDrawsOutsideTheCapitalLineBox() {
        // A line of descender letters stays inside the rows the same capitals use, at every text size.
        for (size in listOf("s", "m", "l", "xl")) {
            fun draw(text: String, case: TextCase): Bitmap {
                Fonts.textCase = case
                val bmp = Bitmap.createBitmap(540, 80, Bitmap.Config.ARGB_8888)
                val g = io.lampyr.glasses.render.Gfx(android.graphics.Canvas(bmp), null)
                with(g) { clear(); tx(text, 0, 10, size) }
                return bmp
            }
            val caps = draw("GJPQY", TextCase.CAPS).litRows()
            val lower = draw("gjpqy", TextCase.NORMAL).litRows()
            assertTrue("$size: $lower inside $caps", lower.first >= caps.first && lower.second <= caps.second)
            assertFalse("$size: lowercase really renders differently", draw("gjpqy", TextCase.NORMAL).sameAs(draw("GJPQY", TextCase.CAPS)))
        }
    }

    @Test fun normalCaseChangesTheBitmapAndCapsRestoresItExactly() = fixture(PowerPrefs.ALWAYS_ON) {
        ui.push(PowerSettings(ui))
        val before = ui.render().bitmap.copy(Bitmap.Config.ARGB_8888, false)
        power.update(power.prefs.copy(textCase = TextCase.NORMAL))
        val normal = ui.render().bitmap
        assertFalse(before.sameAs(normal))
        power.update(power.prefs.copy(textCase = TextCase.CAPS))
        assertTrue("Default caps frames are unchanged (goldens stay identical)", before.sameAs(ui.render().bitmap))
    }
}
