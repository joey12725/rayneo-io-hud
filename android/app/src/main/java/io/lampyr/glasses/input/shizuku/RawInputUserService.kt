package io.lampyr.glasses.input.shizuku

import android.os.*
import android.system.*
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Constructed by Shizuku's app_process as shell, never by Android's Service manager. */
class RawInputUserService : IRawInputService.Stub() {
    private val stopping = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val readers = ConcurrentHashMap<String, Reader>()
    @Volatile private var callback: IRawInputCallback? = null
    private val death = IBinder.DeathRecipient { destroy() }
    init {
        // Covers client death before start()/linkToDeath, including abandoned pending binds.
        thread(name = "evdev-start-watchdog", isDaemon = true) {
            Thread.sleep(15_000)
            if (!started.get()) destroy()
        }
    }
    override fun start(name: String, vendor: Int, product: Int, callback: IRawInputCallback) {
        check(Process.myUid() == 2000) { "Start Shizuku using Wireless debugging (shell uid 2000)" }
        check(started.compareAndSet(false, true)) { "Reader already started" }
        this.callback = callback
        try { callback.asBinder().linkToDeath(death, 0) } catch (_: RemoteException) { destroy(); return }
        val identity = EvdevIdentity(name, vendor, product)
        thread(name = "evdev-scan") {
            var previous = ""
            while (!stopping.get()) {
                var failure = ""
                val discovered = runCatching { discover() }
                    .getOrElse { failure = "Cannot enumerate input: ${it.message}"; emptyList() }
                val devices = EvdevDevices.matching(discovered, identity)
                val paths = devices.flatMap { it.nodes }.toSet()
                readers.entries.filter { it.key !in paths }.forEach { (_, reader) -> reader.close() }
                paths.forEach { path ->
                    if (!readers.containsKey(path)) try {
                        val reader = Reader(path)
                        readers[path] = reader
                        // Publish ownership before accepting events; otherwise first press can race setup.
                    } catch (e: Exception) { failure = "$path: ${e.message}" }
                }
                val live = readers.entries.sortedBy { it.key }
                val exclusive = live.isNotEmpty() && live.size == paths.size && live.all { it.value.exclusive }
                val detail = when {
                    name.isBlank() || vendor == 0 || product == 0 -> "Learn controller keys first (name/vendor/product required)"
                    live.isEmpty() && failure.isBlank() -> "Waiting for learned controller"
                    failure.isNotBlank() -> failure
                    exclusive -> "exclusive"
                    else -> "shared: screen will still wake; ${live.mapNotNull { it.value.grabFailure }.distinct().joinToString()}"
                }
                val summary = "${live.map { it.key }}:$exclusive:$detail"
                if (summary != previous) {
                    send { nodes(devices.firstOrNull()?.name.orEmpty(), live.map { it.key }.toTypedArray(), exclusive, detail) }
                    previous = summary
                }
                live.forEach { it.value.begin() }
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
            }
        }
    }
    private fun discover(): List<EvdevDevice> {
        runCatching { EvdevDevices.parse(File("/proc/bus/input/devices").readText()) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Android 15 SELinux can deny both proc input and sysfs even to shell. getevent is
        // shipped by Android and uses EVIOCGNAME/EVIOCGID on the readable event fds instead.
        val process = ProcessBuilder("/system/bin/getevent", "-i").redirectErrorStream(true).start()
        val output = FutureTask {
            process.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(text.length + count <= 1_048_576) { "getevent output too large" }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        }
        thread(name = "evdev-discovery", isDaemon = true) { output.run() }
        try {
            check(process.waitFor(2, TimeUnit.SECONDS)) { "getevent discovery timed out" }
            check(process.exitValue() == 0) { "getevent cannot enumerate input" }
            return EvdevDevices.parseGetevent(output.get(500, TimeUnit.MILLISECONDS))
        } finally {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            output.cancel(true)
        }
    }
    private fun send(block: IRawInputCallback.() -> Unit) {
        if (stopping.get()) return
        try { callback?.block() } catch (_: RemoteException) { destroy() }
    }
    private inner class Reader(val path: String) {
        private val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        private val closed = AtomicBoolean(false)
        private val reading = AtomicBoolean(false)
        var grabFailure: String? = null; private set
        val exclusive = runCatching { ShellIoctl.grab(fd) }.fold({ true }, { grabFailure = it.cause?.message ?: it.message; false })
        fun begin() {
            if (reading.compareAndSet(false, true)) thread(name = "evdev-${path.substringAfterLast('/')}") {
                val parser = InputEventParser(if (Process.is64Bit()) 24 else 16)
                val buffer = ByteArray(parser.recordSize * 64)
                val poll = StructPollfd().apply { fd = this@Reader.fd; events = OsConstants.POLLIN.toShort() }
                var dropped = false
                try {
                    while (!stopping.get() && !closed.get()) {
                        if (Os.poll(arrayOf(poll), 1_000) == 0) continue
                        check(poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) == 0) { "Input device disappeared" }
                        val count = try { Os.read(fd, buffer, 0, buffer.size) } catch (e: ErrnoException) {
                            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue else throw e
                        }
                        check(count > 0) { "Input device closed" }
                        parser.feed(buffer, count).forEach { event ->
                            if (event.type == 0 && event.code == 3) { dropped = true; send { lost(path) } }
                            else if (event.type == 0 && event.code == 0) dropped = false
                            else if (!dropped && (event.type == 1 || event.type == 3 && event.code in 16..17)) {
                                // Default evdev CLOCK_REALTIME or a previously configured MONOTONIC clock.
                                // Android KeyEvent timestamps use uptime; normalize before cross-path dedupe.
                                val now = SystemClock.uptimeMillis()
                                val stamp = listOf(event.timeMs - System.currentTimeMillis() + now,
                                    event.timeMs - SystemClock.elapsedRealtime() + now, event.timeMs)
                                    .minBy { kotlin.math.abs(it - now) }
                                send { event(path, event.type, event.code, event.value, stamp) }
                            }
                        }
                    }
                } catch (_: Exception) { /* ENODEV, EOF, poll HUP: rescan will reopen. */ }
                finally { close() }
            }
        }
        fun close() {
            if (closed.compareAndSet(false, true)) {
                // Closing fd releases EVIOCGRAB even when explicit ungrab isn't available.
                runCatching { Os.close(fd) }
                readers.remove(path, this)
                send { lost(path) }
            }
        }
    }
    override fun destroy() {
        if (!stopping.compareAndSet(false, true)) return
        readers.values.toList().forEach { it.close() }
        runCatching { callback?.asBinder()?.unlinkToDeath(death, 0) }
        callback = null
        // Shizuku explicitly requires destroy + exit; unbind alone does not kill a UserService.
        kotlin.system.exitProcess(0)
    }
}

/** Hidden API reflection is used ONLY in the unrestricted shell UserService.
 * Linux evdev EVIOCGRAB tests arg != 0 (does not dereference it). Both libcore ioctlInt
 * variants pass &int, so they grab. Close is the ungrab, never ioctlInt(..., 0).
 * AOSP libcore_io_Linux.cpp: Linux_ioctlInt; drivers/input/evdev.c: evdev_do_ioctl.
 */
internal object ShellIoctl {
    fun grab(fd: FileDescriptor) {
        val method = Os::class.java.methods.first { it.name == "ioctlInt" && it.parameterTypes.size in 2..3 }
        val request = 0x40044590 // _IOW('E', 0x90, int), common Android Linux ABIs.
        if (method.parameterTypes.size == 2) method.invoke(null, fd, request)
        else {
            val ref = method.parameterTypes[2].getConstructor(Int::class.javaPrimitiveType).newInstance(1)
            method.invoke(null, fd, request, ref)
        }
    }
}
