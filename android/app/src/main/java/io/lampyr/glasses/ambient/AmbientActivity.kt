package io.lampyr.glasses.ambient

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.core.content.ContextCompat
import io.lampyr.glasses.phone.*
import io.lampyr.glasses.service.GlassesService
import kotlinx.coroutines.*

/** Phone-only prototype controls; no new glasses navigation surface. */
class AmbientActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferences: AmbientPreferences
    private lateinit var status: Note
    private lateinit var conversationList: Stack
    private var enabling = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AmbientPreferences(this)
        lateinit var state: Pill
        lateinit var captureStatus: TextView
        lateinit var modelStatus: FactRow
        lateinit var voiceStatus: FactRow
        lateinit var mutedList: Stack
        lateinit var pause: TextView
        lateinit var turnOn: TextView
        lateinit var turnOff: TextView
        val modes = listOf("auto", "lifelog", "continuous")
        screen("Passive listening", "Records conversations and recognises familiar voices. Hold − on the controller to mute or unmute.") {
            add(context.card(10, Ink.AMBER_DIM) {
                add(context.text("Record only with consent", Type.HEADING, Ink.AMBER))
                add(context.text("Check that the recording light on the glasses is visible before relying on this. Recordings stay private to this app.", Type.SMALL, Ink.TEXT))
            })
            add(context.card(12) {
                add(context.row(10) { add(context.text("Listening", Type.TITLE), 1f); state = add(Pill(context)) })
                captureStatus = add(context.text("", Type.SMALL))
                status = add(context.note())
                add(context.row(10) {
                    turnOn = add(context.button("Turn on", Btn.PRIMARY) {
                        if (ContextCompat.checkSelfPermission(this@AmbientActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            enabling = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 51)
                        } else enable()
                    }, 1f)
                    pause = add(context.button("Pause") { preferences.paused = !preferences.paused }, 1f)
                    turnOff = add(context.button("Turn off", Btn.DANGER) { preferences.enabled = false }, 1f)
                })
            })

            section("Recording")
            add(context.card(14) {
                var mode = modes.indexOf(preferences.mode).coerceAtLeast(0)
                add(context.choice("Audio source", listOf("Auto", "LifeLog", "Continuous"), mode) { mode = it })
                add(context.text("Auto uses LifeLog and switches only if audio arrives without wake events.", Type.SMALL), space = dp(8))
                val days = add(context.field("Keep conversation audio for (days)", preferences.retention.toString(), number = true))
                val gap = add(context.field("Silence that ends a conversation (minutes)", preferences.gapMinutes.toString(), number = true))
                val probe = add(context.toggle("Read cached LifeLog audio", "Experimental. Logs details only", preferences.probeCache) {})
                add(context.button("Save") {
                    val retention = days.value.toIntOrNull(); val minutes = gap.value.toIntOrNull()
                    if (retention !in 1..3650 || minutes !in 1..60) { status.show("Days must be 1 to 3650 and minutes 1 to 60", Tone.WARN); return@button }
                    preferences.retention = retention!!; preferences.gapMinutes = minutes!!; preferences.mode = modes[mode]; preferences.probeCache = probe.isChecked
                    status.show("Recording settings saved", Tone.GOOD)
                })
                add(context.button("Allow location for conversations (optional)", Btn.GHOST) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 52)
                })
            })

            section("Recognising voices")
            add(context.card(14) {
                modelStatus = add(context.fact("Voice model", "Missing"))
                voiceStatus = add(context.fact("Last voice sync"))
                add(context.button("Download the voice model") { GlassesService.instance?.ambient?.downloadModel() })
                add(context.divider())
                val threshold = add(context.field("How strict a voice match must be (30 to 90)", (preferences.speakerThreshold * 100).toInt().toString(), number = true))
                val modelWifi = add(context.toggle("Download the model on Wi-Fi only", null, preferences.modelWifiOnly) {})
                val uploadWifi = add(context.toggle("Upload conversations on Wi-Fi only", null, preferences.uploadWifiOnly) {})
                add(context.button("Save") {
                    val score = threshold.value.toIntOrNull()
                    if (score !in 30..90) { status.show("Strictness must be 30 to 90", Tone.WARN); return@button }
                    preferences.speakerThreshold = score!! / 100f
                    preferences.modelWifiOnly = modelWifi.isChecked; preferences.uploadWifiOnly = uploadWifi.isChecked
                    status.show("Voice settings saved", Tone.GOOD)
                })
            })

            section("People you have muted")
            mutedList = add(context.card(8))

            section("Conversations")
            conversationList = add(context.card(10))
            add(context.text("Speech only, stored privately with timings. Conversations under 30 seconds are skipped. Finished conversations upload to your gateway; the audio used for recognising voices stays on this phone.", Type.SMALL))
        }
        refreshDays()
        scope.launch {
            launch { while (isActive) {
                val on = preferences.enabled; val paused = preferences.paused
                state.show(if (!on) "Off" else if (paused) "Paused" else "On", if (!on) Tone.IDLE else if (paused) Tone.WARN else Tone.GOOD)
                pause.text = if (paused) "Resume" else "Pause"
                turnOn.available(!on); pause.available(on); turnOff.available(on)
                delay(500)
            } }
            val runtime = GlassesService.instance?.ambient
            if (runtime == null) { captureStatus.text = "Open the companion first"; return@launch }
            launch { runtime.model.status.collect { modelStatus.show(it.toString()) } }
            launch { runtime.status.collect { captureStatus.text = it.line() } }
            while (isActive) {
                refreshDays()
                val engine = (GlassesService.instance?.ui?.data as? io.lampyr.glasses.data.LiveDataStore)?.peopleEngine
                voiceStatus.show(engine?.lastSync?.takeIf { n -> n > 0 }?.let { n -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(n)) } ?: "Never")
                mutedList.removeAllViews()
                val muted = engine?.muted.orEmpty()
                if (muted.isEmpty()) mutedList.add(text("Nobody is muted.", Type.SMALL))
                muted.forEach { person ->
                    mutedList.add(row(10) {
                        add(context.text(person.name), 1f)
                        add(context.button("Unmute") { scope.launch { status.show(engine!!.mute(person.id, false).message) } })
                    })
                }
                delay(5000)
            }
        }
    }
    private fun refreshDays() {
        val runtime = GlassesService.instance?.ambient ?: return
        showConversations(runtime.conversations.all())
    }
    internal fun showConversations(records: List<ConversationRecord>) {
        conversationList.removeAllViews()
        if (records.isEmpty()) conversationList.add(text("No conversations yet.", Type.SMALL))
        records.forEach { record ->
            conversationList.add(text("${record.manifest.startedAt} / ${"%.1f".format(record.manifest.speechSec)}s speech / ${record.state}" + record.error?.let { " / $it" }.orEmpty(), Type.SMALL, Ink.TEXT))
        }
    }

    private fun enable() {
        val service = GlassesService.instance
        if (service == null) { status.show("Open the companion first", Tone.WARN); return }
        service.enableAmbientLocation() // Optional. Failure does not block glasses capture.
        preferences.paused = false; preferences.enabled = true
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 51 && enabling) { enabling = false; if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) enable() }
        if (requestCode == 52) GlassesService.instance?.enableAmbientLocation()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
