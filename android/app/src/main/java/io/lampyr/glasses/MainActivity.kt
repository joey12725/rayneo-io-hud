package io.lampyr.glasses

import android.content.*
import android.graphics.Color
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import io.lampyr.glasses.input.*
import io.lampyr.glasses.phone.*
import io.lampyr.glasses.service.GlassesService
import kotlinx.coroutines.*

/** The phone app's shell: four tabs over one bound [GlassesService]. Each tab is a [Page]. */
class MainActivity : AppCompatActivity() {
    var service: GlassesService? = null
        private set
    private var bound = false
    private lateinit var keys: KeyMap
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collect: Job? = null
    private var tick: Job? = null
    private lateinit var pages: List<Page>
    private lateinit var holder: FrameLayout
    private val tabs = mutableListOf<Tab>()
    private var current = 0
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                service = (binder as GlassesService.LocalBinder).service
                service?.refreshForegroundAccess()
                collect?.cancel()
                collect = scope.launch { service!!.preview.frames.collect { frame -> if (frame != null) pages.forEach { it.frame(frame, service?.ui?.locked == true) } } }
                pages[current].refresh()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                collect?.cancel()
            }
        }

    private class Tab(val view: LinearLayout, val icon: ImageView, val label: TextView, val kind: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys = KeyMap(this)
        pages = listOf(HomePage(this), RemotePage(this), ToolsPage(this), SettingsPage(this))
        holder = FrameLayout(this)
        pages.forEach { page ->
            val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; visibility = View.GONE }
            scroll.addView(page.view, ViewGroup.LayoutParams(-1, -2))
            holder.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        val bar = row {
            setBackgroundColor(Ink.SURFACE); setPadding(dp(6), dp(8), dp(6), dp(8))
            listOf("home" to "Home", "remote" to "Remote", "tools" to "Tools", "settings" to "Settings").forEachIndexed { index, (kind, name) ->
                val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER }
                val label = context.text(name, Type.LABEL).apply { isAllCaps = false; letterSpacing = 0.02f; textSize = 12f; gravity = Gravity.CENTER }
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    addView(icon, LinearLayout.LayoutParams(dp(60), dp(32)))
                    addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
                    contentDescription = name
                    setOnClickListener { show(index) }
                }
                tabs += Tab(cell, icon, label, kind)
                add(cell, 1f)
            }
        }
        val root = column { add(holder, 1f); add(divider()); add(bar) }
        frameWindow(root)
        window.navigationBarColor = Ink.SURFACE
        setContentView(root)
        show(savedInstanceState?.getInt("tab") ?: 0)
        startForegroundService(Intent(this, GlassesService::class.java))
        bound = bindService(Intent(this, GlassesService::class.java), connection, BIND_AUTO_CREATE)
        // The checklist asks for notifications and every other permission, with an explanation for each.
        if (savedInstanceState == null) io.lampyr.glasses.settings.OnboardingActivity.showOnFirstRun(this)
    }

    fun show(index: Int) {
        current = index
        for (i in pages.indices) holder.getChildAt(i).visibility = if (i == index) View.VISIBLE else View.GONE
        tabs.forEachIndexed { i, tab ->
            val on = i == index
            tab.icon.setImageDrawable(Glyph(tab.kind, if (on) Ink.GREEN else Ink.MUTED, dp(24)))
            tab.icon.background = if (on) shape(Ink.GREEN_DIM, 99) else pressable(Color.TRANSPARENT, 99)
            tab.label.setTextColor(if (on) Ink.TEXT else Ink.MUTED)
        }
        pages[index].refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab", current) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (current != 0) show(0) else @Suppress("DEPRECATION") super.onBackPressed() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::keys.isInitialized && keys.isController(event.device)) {
            service?.controller?.key(event)
            return true
        }
        // Before a controller has been learned, let any keyboard drive the preview while this
        // screen is in front (first run, emulator, desk testing). Background capture still
        // requires a learned controller so other keyboards are never swallowed. Typing in a
        // text box always goes to the box.
        val typing = currentFocus is EditText
        val letter = if (::keys.isInitialized && !keys.hasLearnedController && !typing) keys.letter(event.keyCode) else null
        if (letter != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) service?.input?.let { it.source = "keyboard"; it.press(letter, false) }
            else if (event.action == KeyEvent.ACTION_UP) service?.input?.let { it.source = "keyboard"; it.release(letter, false) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        io.lampyr.glasses.service.ServiceAccess.resumed(this)
        keys.refresh()
        tick?.cancel()
        tick = scope.launch { while (isActive) { pages[current].refresh(); delay(700) } }
    }
    override fun onPause() { tick?.cancel(); io.lampyr.glasses.service.ServiceAccess.paused(this); super.onPause() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        service?.refreshForegroundAccess()
    }

    override fun onDestroy() {
        keys.close()
        collect?.cancel()
        if (bound) unbindService(connection)
        scope.cancel()
        super.onDestroy()
    }
}
