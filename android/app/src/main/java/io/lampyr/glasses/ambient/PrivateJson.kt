package io.lampyr.glasses.ambient

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val privateJson = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }
internal fun atomicJson(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(tmp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
