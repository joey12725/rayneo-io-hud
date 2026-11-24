package io.lampyr.glasses.input

import android.content.SharedPreferences
import kotlinx.coroutines.*
import io.lampyr.glasses.ui.ButtonClaim
import io.lampyr.glasses.ui.Ui
import io.lampyr.glasses.ui.View
import io.lampyr.glasses.ui.screens.*

/**
 * Only gestures without an existing global action may be assigned. Ids are stored in the `app-shortcuts` prefs and
 * never change (requirement 15.4); a screen that overrides the gesture (View.overrides) simply wins while on top.
 */
data class ShortcutGesture(val id: String, val letter: String, val hold: Boolean, val label: String) {
    val shoulder get() = letter == "l2" || letter == "r"
    /** False while [view] claims this gesture for itself (e.g. the assistant's L2 tap). */
    fun available(view: View) = view.overrides().none { it.letter == letter && it.claim.claims(hold) }
}
val FREE_GESTURES = listOf(
    ShortcutGesture("a.hold", "a", true, "Hold A"),
    ShortcutGesture("r.hold", "r", true, "Hold R"),
    ShortcutGesture("star.hold", "star", true, "Hold Apps"),
    ShortcutGesture("heart.hold", "heart", true, "Hold People"),
    ShortcutGesture("l2.tap", "l2", false, "Tap L2"),
    ShortcutGesture("l2.hold", "l2", true, "Hold L2"),
)

/**
 * Which apps claim which buttons (each app's screen returns the same [ButtonClaim] objects from View.overrides()).
 * Settings > Shortcuts uses it to say where a global shortcut does not apply. An app that adds an override appends here.
 */
val APP_BUTTON_CLAIMS: Map<String, List<ButtonClaim>> = linkedMapOf(
    "joy" to listOf(Joy.QUIET),
)

/** Unknown and removed ids are resolved by the registry, never executed from preferences. */
class ShortcutAssignments(private val prefs: SharedPreferences? = null) {
    private val memory = mutableMapOf<String,String>()
    fun get(gesture: String): String? = prefs?.getString(gesture,null) ?: memory[gesture]
    fun set(gesture: String, action: String?) {
        require(FREE_GESTURES.any { it.id == gesture })
        if (prefs != null) prefs.edit().apply { if(action==null)remove(gesture) else putString(gesture,action) }.apply()
        else if(action==null)memory.remove(gesture) else memory[gesture]=action
    }
}

data class AppShortcut(
    val id: String, val appId: String, val label: String, val safeForShoulders: Boolean,
    val run: suspend () -> String, val state: (() -> String)? = null,
    val prepare: suspend () -> Boolean = { true },
)

class AppShortcuts(private val ui: Ui, private val assignments: ShortcutAssignments) {
    private val registry = linkedMapOf<String,AppShortcut>()
    init {
        register(VoiceMemo.shortcut(ui))
    }
    fun register(action: AppShortcut) { require(action.label.length<=26);registry[action.id]=action }
    fun actions(gesture: ShortcutGesture) = registry.values.filter { action ->
        appEntries(ui).any { it.id == action.appId } && (!gesture.shoulder || action.safeForShoulders)
    }
    fun assigned(gesture: ShortcutGesture) = actions(gesture).firstOrNull { it.id==assignments.get(gesture.id) }
    fun assign(gesture: ShortcutGesture, actionId: String?) {
        require(actionId==null || actions(gesture).any { it.id==actionId })
        assignments.set(gesture.id,actionId);ui.requestRender()
    }
    /**
     * Learned controller buttons, or null when every button exists (no learned map, tests, demo). The service reads
     * the `controller` key map; a gesture on a button the controller does not have (★ on the 8BitDo Micro) is hidden.
     */
    var controllerLetters: () -> Set<String>? = { null }
    /** The gestures Settings lists: buttons the controller has and that some app action can use. */
    fun gestures() = FREE_GESTURES.filter { g -> controllerLetters()?.let { g.letter in it } != false && actions(g).isNotEmpty() }
    /** Launcher apps that keep this gesture for themselves, so the shortcut does not run there. */
    fun overriddenIn(gesture: ShortcutGesture): List<AppEntry> = appEntries(ui).filter { app ->
        APP_BUTTON_CLAIMS[app.id].orEmpty().any { it.letter == gesture.letter && it.claims(gesture.hold) }
    }
    /** "None · not in Hermes": the assignment plus where an app keeps the gesture for itself. */
    fun summary(gesture: ShortcutGesture): String {
        val apps = overriddenIn(gesture)
        return (assigned(gesture)?.label ?: "None") + if (apps.isEmpty()) "" else " · not in " + apps.joinToString(", ") { it.label }
    }
    fun gesture(letter: String,hold: Boolean) = FREE_GESTURES.firstOrNull { it.letter==letter && it.hold==hold && it.available(ui.top) }
    fun action(letter: String,hold: Boolean) = gesture(letter,hold)?.let(::assigned)
    fun run(letter: String,hold: Boolean,allowed: () -> Boolean = { !ui.locked }): Boolean {
        if(!allowed())return false
        val selected=action(letter,hold)?:return false
        // Loaded actions run inline, preserving immediate STOP. Cold actions may await their store.
        ui.scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                if(selected.prepare() && allowed() && action(letter,hold)===selected)ui.flash(selected.run())
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {ui.flash(e.message ?: "Shortcut failed")}
        }
        return true
    }
}
