package io.lampyr.glasses.debug

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only the debug source set owns disk persistence. Rotation sizes count UTF-8 bytes. */
internal class DebugTraceFiles(context: Context) {
    private val directory = File(context.filesDir, "debug").apply { mkdirs() }
    private val file = File(directory, "trace.jsonl")
    private var size = file.length()
    private var writer: BufferedWriter = file.bufferedWriterAppend()
    private fun File.bufferedWriterAppend() = outputStreamAppend().bufferedWriter(Charsets.UTF_8)
    private fun File.outputStreamAppend() = java.io.FileOutputStream(this, true)
    private val flusher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "trace-flush").apply { isDaemon = true } }
    init { flusher.scheduleAtFixedRate({ runCatching { flush() } }, 1, 1, TimeUnit.SECONDS) }
    @Synchronized fun append(entry: Trace.Entry) {
        val line = entry.json().toString() + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8).size
        if (size + bytes > 4 * 1024 * 1024) {
            writer.close()
            val previous = File(directory, "trace.previous.jsonl")
            if (previous.exists()) check(previous.delete())
            check(file.renameTo(previous))
            writer = file.bufferedWriterAppend(); size = 0
        }
        writer.write(line); size += bytes
        if (entry.isError) writer.flush()
    }
    @Synchronized fun flush() = writer.flush()
    @Synchronized fun close() { flusher.shutdownNow(); writer.close() }
    companion object {
        @Volatile private var installed: DebugTraceFiles? = null
        @Synchronized fun install(context: Context) {
            Trace.verbose = true
            if (installed != null) return
            val files = DebugTraceFiles(context); installed = files
            Trace.sink = files::append
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                try { Trace.error("Crash", "Uncaught thread=${thread.name}", failure); files.flush() }
                finally { previous?.uncaughtException(thread, failure) }
            }
        }
    }
}

internal fun Trace.Entry.json() = JSONObject().put("seq", seq).put("t", t).put("up", up).put("cat", cat).put("msg", msg)
