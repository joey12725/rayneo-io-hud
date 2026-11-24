package io.lampyr.glasses.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import io.lampyr.glasses.calls.AndroidCalls
import io.lampyr.glasses.calls.CallSetup
import io.lampyr.glasses.debug.Trace
import io.lampyr.glasses.service.GlassesService
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Permissions for handling phone calls from the glasses. Every one is optional. */
class CallsActivity : Activity() {
    private class Grant(val pill: Pill, val button: View, val permission: String)
    private val grants = mutableListOf<Grant>()
    private lateinit var access: Note
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Phone calls", "See who is calling, answer, and call people from the glasses Phone app. Your usual Phone app stays in charge; a call is only placed after you press Y on the glasses.") {
            fun grant(title: String, detail: String, permission: String, code: Int) {
                add(context.card(10) {
                    val pill = Pill(context)
                    add(context.row(10) { add(context.text(title, Type.HEADING), 1f); add(pill) })
                    add(context.text(detail, Type.SMALL))
                    val button = add(context.button("Allow") { requestPermissions(arrayOf(permission), code) }, space = dp(14))
                    grants += Grant(pill, button, permission)
                })
            }
            section("Needed")
            grant("See calls", "Notices ringing and ongoing calls. Without it, calls never appear on the glasses.", Manifest.permission.READ_PHONE_STATE, 81)
            grant("Answer and hang up", "Lets the controller answer, decline and end a call.", Manifest.permission.ANSWER_PHONE_CALLS, 82)
            section("Phone app on the glasses")
            grant("Contacts", "Caller names, Favorites and Contacts, and \"call mom\". Only saved contacts take over the glasses when they ring. Matched on this phone.", Manifest.permission.READ_CONTACTS, 83)
            grant("Call log", "Recents on the glasses, and the incoming number in basic mode.", Manifest.permission.READ_CALL_LOG, 84)
            grant("Place calls", "Call someone from the glasses or ask ${io.lampyr.glasses.settings.Names.assistant} to. Every call from the glasses waits for your Y.", Manifest.permission.CALL_PHONE, 86)
            if (Build.VERSION.SDK_INT >= 31) grant("Bluetooth headsets", "Names your headsets and lets you choose where call audio goes.", Manifest.permission.BLUETOOTH_CONNECT, 85)
            section("Mute and audio routing")
            add(context.card(12) {
                add(context.text("Linking the glasses or controller as a companion device lets you mute and switch audio during a call (Android 12 and later).", Type.SMALL))
                access = add(context.note())
                add(context.button("Link a device") { CallSetup.associate(this@CallsActivity) { access.show(it) } })
            })
        }
    }
    private fun refresh() {
        grants.forEach { grant ->
            val ok = checkSelfPermission(grant.permission) == PackageManager.PERMISSION_GRANTED
            grant.pill.show(if (ok) "Allowed" else "Off", if (ok) Tone.GOOD else Tone.IDLE)
            grant.button.visibility = if (ok) View.GONE else View.VISIBLE
        }
        access.show(CallSetup.status(this))
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        (GlassesService.instance?.ui?.data?.calls as? AndroidCalls)?.refreshPermissions()
        GlassesService.instance?.refreshForegroundAccess()
        refresh()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 91) refresh()
    }
}

/** A live view of the app's flight recorder: what the Bluetooth, display, input and microphone layers just did. */
class LogActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private lateinit var list: TextView
    private lateinit var empty: TextView
    private var errorsOnly = false
    private var paused = false
    private var shown = -1L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Activity log", "The most recent events, newest first. Useful when something will not connect.") {
            add(context.row(10) {
                add(context.segments(listOf("Everything", "Problems"), 0) { errorsOnly = it == 1; shown = -1; refresh() }, 1f)
                lateinit var pause: TextView
                pause = add(context.button("Pause") { paused = !paused; pause.text = if (paused) "Resume" else "Pause" })
            })
            add(context.card {
                empty = add(context.text("Nothing recorded yet.", Type.SMALL))
                list = add(context.text("", Type.MONO, Ink.TEXT).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.3f) })
            })
            add(context.button("Share the log", Btn.GHOST) {
                val body = entries(2000).joinToString("\n") { "${clock.format(Date(it.t))} ${it.cat}: ${it.msg}" }
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Glasses activity log"); putExtra(Intent.EXTRA_TEXT, body.takeLast(200_000)) }, "Share log"))
            })
        }
        scope.launch { while (isActive) { if (!paused) refresh(); delay(1_000) } }
    }
    private fun entries(max: Int): List<Trace.Entry> =
        Trace.since((Trace.latest - 5000).coerceAtLeast(0), 5000).filter { !errorsOnly || it.isError }.takeLast(max)
    private fun refresh() {
        if (Trace.latest == shown) return
        shown = Trace.latest
        val recent = entries(200).asReversed()
        empty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (errorsOnly) "No problems recorded." else "Nothing recorded yet."
        val text = android.text.SpannableStringBuilder()
        recent.forEach { entry ->
            val start = text.length
            text.append(clock.format(Date(entry.t))).append("  ").append(entry.cat).append('\n')
            text.setSpan(android.text.style.ForegroundColorSpan(if (entry.isError) Ink.RED else Ink.GREEN), start, text.length, 0)
            text.append(entry.msg.take(600)).append("\n\n")
        }
        list.text = text
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Assigns app shortcuts to the controller gestures that have no built-in action. Same assignments as the glasses' Settings → Shortcuts. */
class ShortcutsActivity : Activity() {
    private lateinit var list: Stack
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Controller shortcuts", "Give the button holds that do nothing by default an app action. The glasses' own Settings → Shortcuts changes the same list.") {
            list = add(context.card(10))
        }
        refresh()
    }
    private fun refresh() {
        list.removeAllViews()
        val ui = GlassesService.instance?.ui
        if (ui == null) { list.add(text("Start the companion to assign shortcuts.", Type.SMALL)); return }
        ui.shortcuts.gestures().forEach { gesture ->
            val apps = ui.shortcuts.overriddenIn(gesture)
            if (apps.isNotEmpty()) list.add(text("${gesture.label} is kept by ${apps.joinToString(", ") { it.label }}; the shortcut works everywhere else.", Type.SMALL))
            list.add(button("${gesture.label}: ${ui.shortcuts.assigned(gesture)?.label ?: "None"}") {
                val actions = ui.shortcuts.actions(gesture)
                val labels = listOf("None") + actions.map { it.label }
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(gesture.label)
                    .setSingleChoiceItems(labels.toTypedArray(), actions.indexOfFirst { it.id == ui.shortcuts.assigned(gesture)?.id } + 1) { dialog, index ->
                        ui.shortcuts.assign(gesture, actions.getOrNull(index - 1)?.id); dialog.dismiss(); refresh()
                    }.show()
            })
        }
    }
}
