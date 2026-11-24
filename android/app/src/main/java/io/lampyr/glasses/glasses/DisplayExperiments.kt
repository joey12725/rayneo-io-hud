package io.lampyr.glasses.glasses

import android.content.Context
import io.lampyr.glasses.glasses.protocol.*
import io.lampyr.glasses.render.RasterFit
import io.lampyr.glasses.render.DisplaySize
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant

/** Stock experiments are explicit and stepwise; commands never flash or clear the user's todo list. */
object DisplayExperiments {
    val apps = listOf("com.spotify.music", "com.whatsapp", "org.telegram.messenger", "com.google.android.youtube", "com.google.android.gm")
    val weather = listOf(100, 101, 104, 150, 151, 300, 302, 305, 310, 400, 501, 2001)
    val glyphRows = listOf("▀▄▌▐█░▒▓", "▖▗▘▙▚▛▜▝▞▟", "┌─┬┐│├┼┤└┴┘", "←↑→↓↖↗", "■□▲△●○◆★", "⠿⣿", "😀\uE000")
    fun pad(row: String, columns: Int = 26): String {
        val points = row.codePoints().toArray().take(columns)
        return points.joinToString("") { String(Character.toChars(it)) } + "\u3000".repeat(columns - points.size)
    }
    fun glyphPage(page: Int, rows: Int = 7, columns: Int = 26) = glyphRows.drop(page * rows).take(rows).map { pad(it, columns) }

    private const val ID = "companion_test"
    fun widget(install: Boolean): Command {
        val data = if (install) {
            val content = obj("createSurface" to obj("surfaceId" to ID, "catalogId" to "https://rayneo.com/a2ui/catalogs/glasses-base/v1/catalog.json"),
                "updateComponents" to obj("surfaceId" to ID, "components" to listOf(obj("id" to "root", "component" to "Text", "text" to "Companion 7392"))))
            obj("type" to "a2ui", "id" to ID, "name" to "Companion", "extras" to obj("widgetId" to ID, "name" to "Companion", "uiContent" to content).toString())
        } else obj("id" to ID)
        val command = Command(15, Envelope(18, obj("cmd" to if (install) "widget_install" else "widget_uninstall", "payload" to obj("data" to data)).toString()))
        require(FrameCodec.encode(command.frame(0)).size <= 509) { "A2UI probe exceeds 509-byte frame limit" }
        return command
    }
    fun pattern(): RasterFit.Image {
        val pixels = ByteArray(DisplaySize.PIXELS) { i ->
            val x = i % DisplaySize.W; val y = i / DisplaySize.W
            val on = x == 0 || x == DisplaySize.W - 1 || y == 0 || y == DisplaySize.H - 1 ||
                (y < 72 && (x / 20) % 2 == 0) || (y in 72..143 && (y % 8 == 0 || x % 8 == 0)) ||
                (y >= 144 && ((x + y) % 31 < 2))
            if (on) 255.toByte() else 0
        }
        return RasterFit.Image(DisplaySize.W, DisplaySize.H, 2, pixels)
    }
    fun patternFrame(): Frame {
        val image = pattern()
        val bits = ByteArray(DisplaySize.PACKED_BYTES)
        image.pixels.indices.forEach { i -> if (image.pixels[i] != 0.toByte()) bits[i / 8] = (bits[i / 8].toInt() or (128 ushr (i % 8))).toByte() }
        return DisplayMirror.raster(image).copy(bits = bits)
    }
}

/** Bounded private text log. No addresses, gateway keys, notification contents, or audio payloads. */
class DisplayExperimentLog(context: Context) {
    private val file = File(context.filesDir, "display-experiments.log")
    fun add(label: String, result: String) = synchronized(lock) {
        val line = "${Instant.now()} ${label.replace('\n', ' ').take(120)}: ${result.replace('\n', ' ').take(500)}\n"
        val content = if (file.exists()) file.readText() else ""
        val old = if (content.length > 48_000) content.takeLast(48_000).substringAfter('\n', "") else content
        file.writeText(old + line)
    }
    fun read(): String = synchronized(lock) { if (file.exists()) file.readText() else "No display experiments recorded." }
    companion object { private val lock = Any() }
}
