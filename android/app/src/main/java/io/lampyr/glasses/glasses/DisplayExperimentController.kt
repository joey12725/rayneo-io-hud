package io.lampyr.glasses.glasses

import android.content.Context
import io.lampyr.glasses.glasses.protocol.Commands
import io.lampyr.glasses.glasses.raster.RasterKind
import kotlinx.coroutines.delay

/** Headless owner of every display experiment. UI confirmations and adb both enter here. */
class DisplayExperimentController(context: Context, private val transport: () -> RayNeoTransport? = { GlassesTransports.real }) {
    private val log = DisplayExperimentLog(context)
    private var label = "No experiment selected"
    fun readLog() = log.read()
    fun record(result: String, note: String = "") {
        require(result in setOf("PASS", "FAIL", "UNKNOWN")) { "result must be PASS, FAIL or UNKNOWN" }
        log.add(label, "$result $note")
    }
    suspend fun execute(step: String, args: Map<String, String> = emptyMap()): String {
        if (step == "log") return readLog()
        if (step == "record") { record(args["result"] ?: "UNKNOWN", args["note"].orEmpty()); return readLog() }
        if (step == "stop") { transport()?.endDisplayExperiment(); log.add(label, "Stopped; app output restored"); return "Stopped" }
        val real = transport()
        check(real?.connected == true) { "Connect glasses first" }
        label = "$step ${args.filterKeys { it != "note" }}"
        log.add(label, "REQUEST firmware=${real.deviceStatusFlow.value.firmware ?: "unknown"}")
        try {
            when (step) {
                "firmware" -> { real.status(); delay(1000); log.add(label, "OBSERVED ${real.deviceStatusFlow.value.firmware ?: "no OsVersion reply"}; ${real.raster.note.value}") }
                "glyph" -> {
                    val (cols, rows) = real.textLayout.value
                    val page = args["page"]?.toInt() ?: 0
                    require(page >= 0) { "page must be nonnegative" }
                    real.showGlyphExperimentAndWait(DisplayExperiments.glyphPage(page, rows, cols))
                }
                "notif" -> {
                    val category = args["category"]?.toInt() ?: 0
                    require(category in 0..33) { "category must be 0..33" }
                    val app = args["app"] ?: "io.lampyr.glasses"
                    real.experiment(Commands.notificationSwitch(true))
                    real.experiment(Commands.notification(if ("category" in args) "Category $category" else "Icon probe",
                        if ("category" in args) "Record icon appearance" else app, appId = app, category = category))
                }
                "weather" -> real.experiment(Commands.currentWeather("Companion probe", args["code"]?.toInt() ?: 100, 20, System.currentTimeMillis() / 1000))
                "widget" -> { require(args["install"] in setOf("0", "1")) { "install must be 1 or 0" }; real.experiment(DisplayExperiments.widget(args["install"] == "1")) }
                "pattern" -> {
                    val kind = RasterKind.valueOf((args["kind"] ?: "tvp2").uppercase())
                    val caps = real.raster.capabilities.value
                    val supported = if (kind == RasterKind.TVP2) caps.turbo else caps.glassly?.raster == true
                    check(supported || args["force"] == "1") { "Unconfirmed raster requires explicit force=1" }
                    real.experimentRaster(kind, DisplayExperiments.patternFrame())
                }
                else -> error("Unknown experiment: $step")
            }
        } catch (e: Exception) { log.add(label, "ERROR ${e.message}"); throw e }
        return "Requested. Inspect glasses, then record the result."
    }
}
