package io.lampyr.glasses.input.shizuku

import android.os.Process
import android.os.SystemClock
import android.system.*

/** Optional adb/app_process diagnostic. No manifest entry; must already be the shell user.
 * Does not inject keys or change device configuration. Releases its fd within five seconds. */
object EvdevShellProbe {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 2000)
        val path = args.single()
        require(Regex("/dev/input/event[0-9]+").matches(path))
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        try {
            println("uid=${Process.myUid()} readable=$path ABI=${if (Process.is64Bit()) 64 else 32}")
            println(runCatching { ShellIoctl.grab(fd); "grab=exclusive" }.getOrElse { "grab=shared: $it" })
            val parser = InputEventParser(if (Process.is64Bit()) 24 else 16)
            val buffer = ByteArray(parser.recordSize * 64)
            val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
            val end = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < end) {
                if (Os.poll(arrayOf(poll), 500) > 0) {
                    val count = Os.read(fd, buffer, 0, buffer.size)
                    parser.feed(buffer, count).forEach { println(it) }
                }
            }
        } finally { Os.close(fd); println("closed; grab released") }
    }
}
