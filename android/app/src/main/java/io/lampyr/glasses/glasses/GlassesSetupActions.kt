package io.lampyr.glasses.glasses

import android.content.Context
import io.lampyr.glasses.ambient.CaptureExclusions

/** Shared setup actions: identical selection and meter-only capture for UI and adb. Main thread only. */
object GlassesSetupActions {
    fun select(context: Context, device: RayNeoDevice) {
        check(context.getSharedPreferences("glasses", Context.MODE_PRIVATE).edit()
            .putString("glasses.address", device.classicAddress ?: device.bleAddress)
            .putString("glasses.bleAddress", device.bleAddress).remove(GlassesTransports.LINKED).commit()) { "Cannot persist glasses selection" }
    }
    val testGrid = listOf("12345678901234567890123456", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "!\"#$%&'()*+,-./:;<=>?@[\\]^_",
        "ROW 4: spaces [     ]", "ROW 5: left          right", "ROW 6: full-width grid", "ROW 7: END              #")
    /**
     * Shows the seven-row test grid. Takes the lens back first: if the wearer left our page with the glasses' own
     * touch control, the stock UI owns the lens until something reclaims it, and the grid would go nowhere.
     */
    fun testPattern(real: RayNeoTransport) { real.reclaim(); real.showGrid(testGrid) }
    private var testMic: GlassesMic? = null
    private var exclusion: AutoCloseable? = null
    val testingMic get() = testMic?.takeIf { it.isCapturing() }
    fun micTest(on: Boolean) {
        if (!on) { testMic?.stop(); testMic = null; exclusion?.close(); exclusion = null; return }
        if (testingMic != null) return
        micTest(false)
        val mic = checkNotNull(GlassesTransports.real?.mic) { "Connect glasses first" }
        GlassesTransports.real?.reclaim() // The caption page carries the audio, so it must be ours again first.
        val hold = CaptureExclusions.microphoneTest()
        if (!mic.start { /* Meter only. PCM is discarded, never saved. */ }) {
            hold.close(); error("Microphone unavailable or in use by dictation")
        }
        testMic = mic; exclusion = hold
    }
}
