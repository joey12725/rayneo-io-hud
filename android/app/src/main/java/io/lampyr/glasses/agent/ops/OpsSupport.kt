package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.ui.Ui
import io.lampyr.glasses.ui.View
import io.lampyr.glasses.ui.screens.NowPlayingScreen
import kotlinx.serialization.json.*

/*
 * Shared helpers for the per-app ops files (BeeperOps, T3Ops, MediaOps, ...). Not an OpProvider itself:
 * the registry only loads classes named `<App>Ops` from PLANNED_PROVIDERS.
 */

/** Opening a screen while locked would leave it hidden behind the blank lock display. */
internal fun lockedReason(ui: Ui): String? = if (ui.locked) "The glasses are locked; call glasses_unlock first" else null

/** Lists returned to the model are capped (AGENT_OPS.md: 25 items). */
internal const val MAX_ITEMS = 25

/** Message and document text returned to the model is capped (AGENT_OPS.md: 4,000 characters). */
internal fun clip(text: String, max: Int = AgentScreen.MAX_TEXT): String = if (text.length <= max) text else text.take(max - 1) + "…"

/**
 * Resolves [wanted] against [items]: exact id first, then a case-insensitive exact name, then a name that contains
 * it. No match fails with `failed` (naming the list tool); several fail with `ambiguous` plus up to 8 candidates.
 */
internal fun <T> AgentCall.resolve(
    wanted: String,
    items: List<T>,
    what: String,
    listTool: String,
    id: (T) -> String,
    name: (T) -> String,
): T {
    items.firstOrNull { id(it) == wanted }?.let { return it }
    val needle = wanted.trim()
    val exact = items.filter { name(it).equals(needle, ignoreCase = true) }
    val matches = exact.ifEmpty { items.filter { name(it).contains(needle, ignoreCase = true) } }
    return when (matches.size) {
        1 -> matches.single()
        0 -> fail(Codes.FAILED, "No $what matching \"$wanted\"; use $listTool")
        else -> fail(Codes.AMBIGUOUS, "Several ${what}s match \"$wanted\"; pass the id",
            JsonArray(matches.take(8).map { buildJsonObject { put("id", id(it)); put("name", name(it)) } }))
    }
}

/** Pushes [view] and verifies it is on top: a ringing call keeps new screens out, which must not read as success. */
internal fun AgentCall.show(view: View): View {
    // ui.push ignores a view whose id matches the top (one PersonCard "card" or HealthDetail "hd" for another
    // person or metric would stay up while we report the new one): swap it instead. A ringing call is on top
    // with its own id, so this never replaces the call screen.
    if (ui.stack.isNotEmpty() && ui.top !== view && ui.top.id == view.id) ui.replaceTop(view) else ui.push(view)
    ui.requestRender()
    if (ui.top !== view) fail(Codes.UNAVAILABLE, "A phone call is ringing on the glasses; try again after it")
    return ui.top
}

/** After the screen-level open helpers (ui.open / ui.openTarget): fail when a ringing call kept the screen out. */
internal fun AgentCall.expectTop(description: String, check: (View) -> Boolean) {
    ui.requestRender()
    if (!check(ui.top)) fail(Codes.UNAVAILABLE, "Could not show $description on the glasses (a phone call may be ringing)")
}

/** Shows Now Playing the way the media screens do after a successful start: reuse it on the stack, else Home > Now Playing. */
internal fun showNowPlaying(ui: Ui): NowPlayingScreen? {
    val i = ui.stack.indexOfFirst { it is NowPlayingScreen }
    if (i >= 0) { while (ui.stack.lastIndex > i) ui.pop() }
    else { ui.goHome(); ui.push(NowPlayingScreen(ui)) }
    ui.requestRender()
    return ui.top as? NowPlayingScreen
}

/** A data row with only the non-blank string fields. */
internal fun JsonObjectBuilder.putText(key: String, value: String?) { if (!value.isNullOrBlank()) put(key, value) }
