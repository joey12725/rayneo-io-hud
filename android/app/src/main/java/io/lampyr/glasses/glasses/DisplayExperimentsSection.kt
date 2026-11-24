package io.lampyr.glasses.glasses

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import io.lampyr.glasses.glasses.protocol.*
import io.lampyr.glasses.glasses.raster.*
import io.lampyr.glasses.phone.*
import io.lampyr.glasses.service.GlassesService
import kotlinx.coroutines.*

/** Display settings and the firmware experiments, away from everyday pairing. */
class DisplayLabActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Display lab", "Try what the lens can draw and write down what you see. Nothing here updates firmware.") {
            DisplayExperimentsSection(this@DisplayLabActivity, this, scope)
        }
    }
    override fun onStop() { GlassesTransports.real?.endDisplayExperiment(); super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class DisplayExperimentsSection(private val activity: Activity, root: Stack, private val scope: CoroutineScope) {
    private val prefs = activity.getSharedPreferences("glasses", 0)
    private val controller = DisplayExperimentController(activity)
    private val current = activity.note()
    private var test = ""
    private var glyph = 0
    private var appIndex = 0
    private var category = 0
    private var weatherIndex = 0
    private var job: Job? = null
    init {
        with(root) {
            add(context.card(10, Ink.AMBER_DIM) {
                add(context.text("Don't update the firmware yet", Type.HEADING, Ink.AMBER))
                add(context.text("Note the version the glasses shipped with and decline any update offered.", Type.SMALL, Ink.TEXT))
            })

            section("How the app draws")
            add(context.card(16) {
                fun setting(title: String, key: String, values: List<String>, default: String) =
                    add(context.choice(title, values, values.indexOf(prefs.getString(key, default))) { index ->
                        if (prefs.getString(key, default) != values[index]) {
                            prefs.edit().putString(key, values[index]).apply()
                            GlassesService.instance?.let { it.updateDisplay(); it.ui.requestRender() }
                        }
                    })
                setting("Display mode", "display.mode", DisplayMode.entries.map { it.name }, "AUTO")
                add(context.text("Auto picks pictures when the glasses report they can draw them. Text keeps the 26×7 caption grid. Raster insists on pictures and reports a failure if they are refused.", Type.SMALL), space = dp(8))
                val levels = listOf(2, 4, 8, 16)
                add(context.choice("Grey levels", levels.map { it.toString() }, levels.indexOf(prefs.getInt("display.levels", 16))) { index ->
                    if (prefs.getInt("display.levels", 16) != levels[index]) prefs.edit().putInt("display.levels", levels[index]).apply()
                })
                setting("Block graphics in text mode", "display.blocks", listOf("OFF", "HALF", "QUADRANT"), "OFF")
                add(context.text("Leave off until the glyph test below passes. Half gives 26×14 blocks, quadrant 52×14.", Type.SMALL), space = dp(8))
            })

            section("Experiments, in order")
            add(context.card(10) {
                fun step(number: String, title: String, detail: String, action: () -> Unit): TextView {
                    add(context.row(12) {
                        gravity = android.view.Gravity.TOP
                        add(context.text(number, Type.HEADING, Ink.GREEN).apply { minWidth = dp(26) })
                        add(context.column(3) { add(context.text(title, Type.HEADING)); add(context.text(detail, Type.SMALL)) }, 1f)
                    })
                    return add(context.button("Run") { action() }, space = dp(10))
                }
                step("1", "Read the firmware version", "Record this before anything else.") { run("firmware") }
                add(context.divider())
                step("2", "Glyph probe", "Shows the next page of test characters each time.") {
                    run("glyph", mapOf("page" to glyph.toString()))
                    val rows = GlassesTransports.real?.textLayout?.value?.second ?: 7
                    glyph = (glyph + 1) % ((DisplayExperiments.glyphRows.size + rows - 1) / rows)
                }
                add(context.divider())
                step("3a", "Notification app icons", "Cycles through ${DisplayExperiments.apps.size} stock app icons. Changes the stock notification card.") {
                    run("notif", mapOf("app" to DisplayExperiments.apps[appIndex])); appIndex = (appIndex + 1) % DisplayExperiments.apps.size
                }
                add(context.divider())
                step("3b", "Notification categories", "Cycles through categories 0 to 33.") { run("notif", mapOf("category" to category.toString())); category = (category + 1) % 34 }
                add(context.divider())
                step("4", "Weather icons", "Cycles through the stock weather codes. Changes the stock weather card.") {
                    run("weather", mapOf("code" to DisplayExperiments.weather[weatherIndex].toString())); weatherIndex = (weatherIndex + 1) % DisplayExperiments.weather.size
                }
                add(context.divider())
                step("5", "Dashboard widget", "Installs a small text widget. Disabled on stock firmware 1.0.5.5 and later; a successful write is not proof it shows, so look at the dashboard.") { run("widget", mapOf("install" to "1")) }
                add(context.button("Remove the test widget", Btn.GHOST) { run("widget", mapOf("install" to "0")) })
                add(context.divider())
                add(context.row(12) {
                    gravity = android.view.Gravity.TOP
                    add(context.text("6", Type.HEADING, Ink.GREEN).apply { minWidth = dp(26) })
                    add(context.column(3) { add(context.text("Picture pattern", Type.HEADING)); add(context.text("Draws a test picture. The plain buttons work only when the glasses report support.", Type.SMALL)) }, 1f)
                })
                val supported = RasterKind.entries.associateWith { kind -> add(context.button("Draw with $kind") { raster(kind, false) }) }
                for (kind in RasterKind.entries) add(context.button("Try $kind anyway…", Btn.GHOST) {
                    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Try unconfirmed $kind pictures?")
                        .setMessage("This opens a custom display page and may be refused while LifeLog is armed. It does not update firmware. Only a real reply from the glasses counts as success.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Try once") { _, _ -> raster(kind, true) }.show()
                })
                scope.launch { while (isActive) {
                    val caps = GlassesTransports.real?.raster?.capabilities?.value
                    supported.forEach { (kind, button) -> button.available(when (kind) { RasterKind.TVP2 -> caps?.turbo == true; RasterKind.GLASSLY -> caps?.glassly?.raster == true }) }
                    delay(500)
                } }
            })

            section("What happened")
            add(context.card(14) {
                add(current)
                val notes = add(context.field("What you saw", hint = "What appeared, clipping, delay, LifeLog armed?", lines = 2))
                add(context.row(10) {
                    for ((result, style) in listOf("PASS" to Btn.PRIMARY, "FAIL" to Btn.DANGER, "UNKNOWN" to Btn.TONAL)) add(context.button(result.lowercase().replaceFirstChar { it.uppercase() }, style) {
                        controller.record(result, notes.value); current.show("$test\nRecorded $result ${notes.value}".trim(), if (result == "PASS") Tone.GOOD else if (result == "FAIL") Tone.BAD else Tone.IDLE); notes.value = ""
                    }, 1f)
                })
                add(context.divider())
                add(context.button("Stop and restore the normal display") { run("stop") })
                add(context.button("Share the results log", Btn.GHOST) {
                    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "RayNeo display experiments"); putExtra(Intent.EXTRA_TEXT, controller.readLog()) }
                    activity.startActivity(Intent.createChooser(intent, "Share display results"))
                })
            })
        }
    }
    private fun run(step: String, args: Map<String, String> = emptyMap()) {
        job?.cancel(); test = "$step ${args.entries.joinToString(" ") { "${it.key}=${it.value}" }}".trim(); current.show("Running $test…")
        job = scope.launch {
            try { current.show("$test\n${controller.execute(step, args)}") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { current.show("$test\n${e.message}", Tone.BAD) }
        }
    }
    private fun raster(kind: RasterKind, forced: Boolean) {
        run("pattern", mapOf("kind" to kind.name.lowercase(), "force" to if (forced) "1" else "0"))
    }
}
