package io.lampyr.glasses.render.avatar

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Loads avatar packs from bytes, the bundled assets or a directory. */
object AvatarPacks {
    /** Bundled CC0 placeholder: `assets/avatar/default/`. */
    const val BUNDLED_DIR = "avatar/default"
    const val MANIFEST = "manifest.json"

    fun load(manifestText: String, atlas: (String) -> InputStream): AtlasAvatarPack {
        val manifest = AvatarManifest.parse(manifestText)
        val bitmap = decode(atlas(manifest.atlasFile).use { it.readBytes() }, manifest)
        return AtlasAvatarPack(manifest, bitmap)
    }

    fun fromAssets(context: Context, dir: String = BUNDLED_DIR): AtlasAvatarPack =
        load(context.assets.open("$dir/$MANIFEST").bufferedReader().use { it.readText() }) { context.assets.open("$dir/$it") }

    fun fromDirectory(dir: File): AtlasAvatarPack =
        load(File(dir, MANIFEST).readText()) { File(dir, it).inputStream() }

    /** The bundled pack, or null if even that cannot load (callers then draw [drawAvatarPlaceholder]). */
    fun bundledOrNull(context: Context): AvatarPack? = runCatching { fromAssets(context) }.getOrNull()

    /** Largest atlas side and area accepted (16 MP is 64 MB decoded); a tiny compressed PNG can claim a huge size. */
    const val MAX_ATLAS_SIDE = 8192
    const val MAX_ATLAS_PIXELS = 16L * 1024 * 1024

    /**
     * Decodes an atlas after checking its declared size from the PNG header: never larger than [MAX_ATLAS_SIDE] /
     * [MAX_ATLAS_PIXELS], and (with a [manifest]) never more than one cell wider or taller than the grid it describes.
     */
    internal fun decode(bytes: ByteArray, manifest: AvatarManifest? = null): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw AvatarPackException("atlas is not a readable PNG")
        if (w > MAX_ATLAS_SIDE || h > MAX_ATLAS_SIDE || w.toLong() * h > MAX_ATLAS_PIXELS)
            throw AvatarPackException("atlas is too large (${w}x$h; at most ${MAX_ATLAS_SIDE}px a side and ${MAX_ATLAS_PIXELS / (1024 * 1024)} MP)")
        if (manifest != null && (w > (manifest.cols + 1) * manifest.cellW || h > (manifest.rows + 1) * manifest.cellH))
            throw AvatarPackException("atlas is ${w}x$h but the manifest describes ${manifest.cols * manifest.cellW}x${manifest.rows * manifest.cellH}")
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
        return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) } catch (e: OutOfMemoryError) { throw AvatarPackException("atlas is too large to load") }
            ?: throw AvatarPackException("atlas is not a readable PNG")
    }
}

/**
 * The phone-side avatar setting (`avatar.pack`) and imported packs.
 *
 * Values of `avatar.pack`: `default` (bundled placeholder) or `file:<id>` (a pack under `filesDir/avatar/<id>/`,
 * imported from a zip or downloaded from an https URL). Any load failure degrades to the bundled pack, and a
 * broken bundled pack degrades to the drawn placeholder; the glasses never lose their avatar.
 *
 * Settings-page hooks: [importPackFromZip], [importPackFromUrl], [resetToDefault], [currentPack], [currentInfo].
 * Imports block (I/O, network): call them off the main thread.
 */
class AvatarPackStore(
    context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
) {
    private val app = context.applicationContext ?: context
    private val root = File(context.filesDir, "avatar")

    data class Info(val setting: String, val id: String, val name: String, val license: String, val states: Int)

    var setting: String
        get() = prefs.getString(KEY, DEFAULT) ?: DEFAULT
        private set(value) { prefs.edit().putString(KEY, value).apply() }

    /** The pack the glasses should draw now (never throws; may be null only if the bundled assets are broken). */
    fun currentPack(): AvatarPack? {
        val s = setting
        if (s.startsWith(FILE)) {
            val dir = packDir(s.removePrefix(FILE))
            if (dir != null) try { return AvatarPacks.fromDirectory(dir) } catch (_: Exception) {} catch (_: OutOfMemoryError) {}
        }
        return AvatarPacks.bundledOrNull(app)
    }

    /** The current pack's manifest without decoding its atlas (cheap enough for a settings page refresh). */
    fun currentManifest(): AvatarManifest? {
        val s = setting
        if (s.startsWith(FILE)) packDir(s.removePrefix(FILE))?.let { dir ->
            runCatching { return AvatarManifest.parse(File(dir, AvatarPacks.MANIFEST).readText()) }
        }
        return runCatching { AvatarManifest.parse(app.assets.open("${AvatarPacks.BUNDLED_DIR}/${AvatarPacks.MANIFEST}").bufferedReader().use { it.readText() }) }.getOrNull()
    }

    fun currentInfo(): Info? = currentManifest()?.let { Info(setting, it.id, it.name, it.license, it.states.size) }

    fun resetToDefault() { setting = DEFAULT }

    /** Imports a zip picked through the Storage Access Framework and makes it the current pack. */
    fun importPackFromZip(uri: Uri): AvatarManifest {
        val stream = app.contentResolver.openInputStream(uri) ?: throw AvatarPackException("could not open the file")
        return stream.use { importZip(it) }
    }

    /** Imports a pack zip from any stream (used by [importPackFromZip], [importPackFromUrl] and tests). */
    fun importZip(stream: InputStream): AvatarManifest = install(unzip(stream))

    /**
     * Downloads a pack over https: either a `.zip`, or a `manifest.json` whose atlas sits next to it. The copy under
     * filesDir is the cache; the glasses never fetch at draw time.
     */
    fun importPackFromUrl(url: String): AvatarManifest {
        val u = URL(url)
        if (u.protocol != "https") throw AvatarPackException("avatar pack URLs must use https")
        val bytes = fetch(u)
        val files = if (isZip(bytes)) unzip(ByteArrayInputStream(bytes)) else {
            val manifest = AvatarManifest.parse(bytes.toString(Charsets.UTF_8))
            mapOf(AvatarPacks.MANIFEST to bytes, manifest.atlasFile to fetch(URL(u, manifest.atlasFile)))
        }
        return install(files)
    }

    private fun install(files: Map<String, ByteArray>): AvatarManifest {
        val manifestBytes = files[AvatarPacks.MANIFEST] ?: throw AvatarPackException("the pack has no manifest.json")
        val manifest = AvatarManifest.parse(manifestBytes.toString(Charsets.UTF_8))
        val atlasBytes = files[manifest.atlasFile] ?: throw AvatarPackException("the pack has no ${manifest.atlasFile}")
        // Validates the atlas size against the manifest before anything is written.
        AtlasAvatarPack(manifest, AvatarPacks.decode(atlasBytes, manifest))
        val dir = File(root, manifest.id)
        val staging = File(root, ".staging-${manifest.id}")
        staging.deleteRecursively(); staging.mkdirs()
        File(staging, AvatarPacks.MANIFEST).writeBytes(manifestBytes)
        File(staging, manifest.atlasFile).writeBytes(atlasBytes)
        files.keys.filter { it.equals("LICENSE.txt", true) || it.equals("LICENSE", true) || it.equals("README.md", true) }
            .forEach { File(staging, it).writeBytes(files.getValue(it)) }
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) throw IOException("could not install the pack")
        setting = FILE + manifest.id
        return manifest
    }

    private fun packDir(id: String): File? =
        File(root, id).takeIf { ID.matches(id) && File(it, AvatarPacks.MANIFEST).isFile }

    companion object {
        const val PREFS = "avatar"
        const val KEY = "avatar.pack"
        const val DEFAULT = "default"
        const val FILE = "file:"
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_ENTRIES = 64
        private val ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

        /**
         * Reads a pack zip into memory: manifest.json, PNGs and a licence, flattened to their file names. A single
         * top-level folder is allowed (zips made by "compress folder"). Paths are never used for writing.
         */
        fun unzip(stream: InputStream): Map<String, ByteArray> {
            val files = linkedMapOf<String, ByteArray>()
            var total = 0L
            var entries = 0
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw AvatarPackException("the zip has too many files")
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/').substringAfterLast('/')
                    if (name.startsWith(".") || name.isEmpty() || e.name.startsWith("__MACOSX")) continue
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val n = zip.read(chunk); if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw AvatarPackException("the pack is larger than 16 MB")
                        buf.write(chunk, 0, n)
                    }
                    files[name] = buf.toByteArray()
                }
            }
            if (files.isEmpty()) throw AvatarPackException("not a zip file, or it is empty")
            return files
        }

        private fun fetch(url: URL): ByteArray {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            try {
                if (c.responseCode !in 200..299) throw AvatarPackException("download failed (HTTP ${c.responseCode})")
                if (c.url.protocol != "https") throw AvatarPackException("the pack URL redirected away from https")
                return c.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(chunk); if (n < 0) break
                        if (out.size() + n > MAX_BYTES) throw AvatarPackException("the pack is larger than 16 MB")
                        out.write(chunk, 0, n)
                    }
                    out.toByteArray()
                }
            } finally { c.disconnect() }
        }
    }
}
