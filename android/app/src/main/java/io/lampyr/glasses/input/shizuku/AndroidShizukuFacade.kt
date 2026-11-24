package io.lampyr.glasses.input.shizuku

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import rikka.shizuku.Shizuku

/** Main-thread lifecycle; acquire the runtime wake lock on the Binder thread before posting input. */
class AndroidShizukuFacade(private val context: Context, private val wakeOnEvent: () -> Unit) : ShizukuInputFacade {
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ShizukuInputFacade.Listener? = null
    private var identity: EvdevIdentity? = null
    private var state = ShizukuInputState()
    private var remote: IRawInputService? = null
    private var binding = false
    private var generation = 0
    private var retryCount = 0
    private var active = false
    private val args = Shizuku.UserServiceArgs(ComponentName(context, RawInputUserService::class.java))
        .daemon(false).processNameSuffix("controller_input").tag("controller-evdev").version(1).debuggable(false)
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener { disconnected("Shizuku stopped; accessibility fallback") }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val serviceDeath = IBinder.DeathRecipient { handler.post { disconnected("Input reader died; retrying") } }
    private val retry = Runnable { if (active) refresh() }
    private val bindTimeout = Runnable {
        if (active && binding && remote == null) {
            unbind()
            publish(state.copy(bound = false, nodes = emptyList(), exclusive = false, detail = "Reader bind timed out; retrying"))
            scheduleRetry()
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!active || !binding) { runCatching { Shizuku.unbindUserService(args, this, true) }; return }
            handler.removeCallbacks(bindTimeout)
            val session = generation
            try {
                binder.linkToDeath(serviceDeath, 0)
                remote = IRawInputService.Stub.asInterface(binder)
                publish(state.copy(bound = true, detail = "Finding learned controller"))
                val id = identity ?: EvdevIdentity("", 0, 0)
                remote!!.start(id.name, id.vendor, id.product, object : IRawInputCallback.Stub() {
                    override fun event(node: String, type: Int, code: Int, value: Int, timeMs: Long) {
                        // A partial wake lock must precede the main-thread queue and engine work.
                        wakeOnEvent()
                        handler.post { if (active && session == generation) listener?.event(node, RawInputEvent(timeMs, type, code, value)) }
                    }
                    override fun nodes(name: String, paths: Array<out String>, exclusive: Boolean, detail: String) {
                        handler.post {
                            if (active && session == generation) {
                                retryCount = 0
                                publish(state.copy(bound = true, controller = name, nodes = paths.toList(), exclusive = exclusive, detail = detail))
                            }
                        }
                    }
                    override fun lost(node: String) {
                        wakeOnEvent()
                        handler.post { if (active && session == generation) listener?.lost(node) }
                    }
                })
            } catch (e: Exception) { disconnected("Reader failed: ${e.message}") }
        }
        override fun onServiceDisconnected(name: ComponentName) { if (active && binding) disconnected("Input reader disconnected; retrying") }
    }
    private fun publish(value: ShizukuInputState) { state = value; listener?.state(state) }
    override fun start(identity: EvdevIdentity?, listener: ShizukuInputFacade.Listener) {
        this.identity = identity; this.listener = listener; active = true
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        refresh()
    }
    override fun refresh() {
        if (!active) return
        val available = availability(context)
        val installed = available.installed
        val running = available.running
        val granted = available.granted
        val shell = granted && runCatching { Shizuku.getUid() == 2000 }.getOrDefault(false)
        if (!shell) {
            unbind()
            publish(ShizukuInputState(installed, running, granted, detail = when {
                !running -> "Start Shizuku after each reboot; accessibility fallback"
                !granted -> "Grant Shizuku permission; accessibility fallback"
                else -> "Start Shizuku via Wireless debugging as shell uid 2000"
            }))
            return
        }
        publish(state.copy(installed = installed, running = true, granted = true))
        if (!binding && identity != null) {
            binding = true
            try {
                Shizuku.bindUserService(args, connection)
                handler.postDelayed(bindTimeout, 12_000)
            } catch (e: Exception) { disconnected("Cannot bind reader: ${e.message}") }
        } else if (identity == null) publish(state.copy(detail = "Learn controller keys first"))
    }
    override fun requestPermission() {
        if (!active) return
        runCatching {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) refresh()
                else if (Shizuku.shouldShowRequestPermissionRationale()) publish(state.copy(detail = "Allow RayNeo Companion in Shizuku's authorized apps"))
                else Shizuku.requestPermission(733)
            }
        }.onFailure { publish(state.copy(detail = "Permission request failed: ${it.message}")) }
    }
    private fun scheduleRetry() {
        handler.removeCallbacks(retry)
        if (active) handler.postDelayed(retry, (1_000L shl retryCount++.coerceAtMost(5)).coerceAtMost(30_000))
    }
    private fun disconnected(detail: String) {
        if (!active) return
        unbind()
        val available = availability(context)
        publish(state.copy(running = available.running, granted = available.granted, bound = false, nodes = emptyList(), exclusive = false, detail = detail))
        scheduleRetry()
    }
    private fun unbind() {
        generation++
        handler.removeCallbacks(bindTimeout)
        val reader = remote
        runCatching { reader?.asBinder()?.unlinkToDeath(serviceDeath, 0) }
        remote = null
        val wasBinding = binding; binding = false
        // Also close directly if the Shizuku binder has died before it could process unbind.
        runCatching { reader?.destroy() }
        if (wasBinding) runCatching { Shizuku.unbindUserService(args, connection, true) }
    }
    override fun close() {
        active = false
        unbind()
        handler.removeCallbacksAndMessages(null)
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        listener = null
    }
    companion object {
        fun availability(context: Context): ShizukuInputState {
            val installed = runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)
            val running = Shizuku.pingBinder()
            val granted = running && runCatching { !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
            return ShizukuInputState(installed = installed, running = running, granted = granted)
        }
    }
}
