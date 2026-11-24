package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import kotlinx.serialization.json.*

/**
 * Home Assistant (the glasses "Home" app, id ha): read the panel, run a scene, set the wake-up alarm. Control stays
 * available even though the user wants Home Assistant notifications suppressed. Scenes and the alarm act on their house.
 */
object HomeAssistantOps : OpProvider {
    private val TIME = Regex("^(1[0-2]|0?[1-9]):[0-5][0-9] (AM|PM)$")

    override fun ops() = listOf(
        OpSpec(
            "ha.panel", "Read Home Assistant",
            "The user's Home Assistant panel: scenes (id, name), the wake-up alarm (time, style) and 3D printers (state, progress, time left, detail).",
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            ui.data.ha.refresh()
            val p = ui.data.ha.panel
            ok(data = buildJsonObject {
                put("scenes", JsonArray(p.scenes.map { s -> buildJsonObject { put("id", s.id); put("name", s.name) } }))
                put("alarm", buildJsonObject { put("time", p.alarm.time); put("style", p.alarm.style) })
                put("printers", JsonArray(p.printers.map { pr ->
                    buildJsonObject {
                        put("id", pr.id); put("name", pr.name); put("state", pr.state)
                        if (pr.progress > 0) put("progressPercent", (pr.progress * 100).toInt())
                        if (pr.timeLeftSec > 0) put("timeLeftSec", pr.timeLeftSec)
                        if (pr.layers > 0) put("layer", "${pr.layer}/${pr.layers}")
                        putText("detail", clip(pr.detail, 400))
                    }
                }))
            })
        },

        OpSpec(
            "ha.run_scene", "Run a Home Assistant scene",
            "Run one Home Assistant scene (id or name from ha_panel), e.g. lights for guests or going to sleep.",
            params = listOf(Param.string("scene", "Scene id or name", required = true, maxLength = 200)),
            risk = Risk.PHYSICAL, timeoutMs = 15_000,
            available = { ui -> if (ui.data.ha.panel.scenes.isEmpty()) "No Home Assistant scenes are loaded" else null },
            summary = { call -> call.strOrNull("scene").orEmpty() },
        ) {
            val scene = resolve(str("scene"), ui.data.ha.panel.scenes, "scene", "ha_panel", { it.id }, { it.name })
            done(ui.data.ha.scene(scene.id), "Ran ${scene.name}")
        },

        OpSpec(
            "ha.set_alarm", "Set the wake-up alarm",
            "Set the user's Home Assistant wake-up alarm time (\"7:00 AM\") and/or style (Gentle or Harsh).",
            params = listOf(
                Param.string("time", "Time like 7:00 AM", maxLength = 8),
                Param.string("style", "Gentle or Harsh", enum = listOf("Gentle", "Harsh")),
            ),
            risk = Risk.PHYSICAL, tool = false,
            summary = { call -> listOfNotNull(call.strOrNull("time"), call.strOrNull("style")).joinToString(" · ") },
        ) {
            val time = strOrNull("time")?.uppercase()?.replace(Regex("\\s+"), " ")
            if (time != null && !TIME.matches(time)) fail(Codes.INVALID_ARGS, "time must look like 7:00 AM")
            val style = strOrNull("style")
            if (time == null && style == null) fail(Codes.INVALID_ARGS, "Pass time and/or style")
            done(ui.data.ha.alarm(time, style), "Alarm ${listOfNotNull(time, style).joinToString(", ")}")
        },
    )
}
