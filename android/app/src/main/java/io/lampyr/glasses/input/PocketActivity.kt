package io.lampyr.glasses.input

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.*
import android.view.*

/** Keeps Android interactive over the still-secure keyguard; black does not mean screen off. */
class PocketActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val exit = Runnable { exitPocket() }
    private var exitScheduled = false
    private fun exitPocket() {
        android.widget.Toast.makeText(this, "Pocket mode off", android.widget.Toast.LENGTH_SHORT).show()
        runCatching {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel("pocket", "Pocket mode", android.app.NotificationManager.IMPORTANCE_LOW))
            manager.notify(24, android.app.Notification.Builder(this, "pocket")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Pocket mode off").setTimeoutAfter(3_000).build())
        }.onFailure { android.util.Log.e("PocketActivity", "Could not post pocket exit notification", it) }
        finish()
    }
    private lateinit var keys: KeyMap
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys = KeyMap(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        setContentView(View(this).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "Pocket mode. Hold three fingers for two seconds or press a phone volume key to exit."
            setOnTouchListener { _, event ->
                val deliberate = event.pointerCount == 3 && event.actionMasked !in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL)
                if (deliberate && !exitScheduled) {
                    exitScheduled = true; this@PocketActivity.handler.postDelayed(exit, 2_000)
                } else if (!deliberate) {
                    exitScheduled = false; this@PocketActivity.handler.removeCallbacks(exit)
                }
                true
            }
        })
        immerse()
        io.lampyr.glasses.service.ServiceAccess.start(this)
    }
    @Suppress("DEPRECATION")
    private fun immerse() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immerse() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keys.isController(event.device)) {
            io.lampyr.glasses.service.GlassesService.instance?.controller?.key(event)
            return true
        }
        if (event.keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_UP) exitPocket()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onBackPressed() { exitPocket() }
    override fun onResume() { super.onResume(); active = true; io.lampyr.glasses.service.ServiceAccess.resumed(this) }
    override fun onPause() { active = false; exitScheduled = false; handler.removeCallbacks(exit); io.lampyr.glasses.service.ServiceAccess.paused(this); super.onPause() }
    override fun onDestroy() { keys.close(); super.onDestroy() }
    companion object {
        @Volatile var active = false; private set
        fun intent(context: Context) = Intent(context, PocketActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
