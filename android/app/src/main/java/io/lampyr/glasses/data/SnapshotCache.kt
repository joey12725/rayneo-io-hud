package io.lampyr.glasses.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Small atomic snapshots. Hydrated before the first frame; failed requests never replace them. */
class SnapshotCache(private val directory: File) {
    private val values = mutableMapOf<String, JsonElement>()
    private val lock = Mutex()
    init {
        directory.mkdirs()
        directory.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            runCatching { val row = gatewayJson.parseToJsonElement(file.readText()).jsonObject; values[row.getValue("key").jsonPrimitive.content] = row.getValue("value") }
        }
    }
    operator fun get(key: String): JsonElement? = values[key]
    suspend fun put(key: String, value: JsonElement) {
        values[key] = value
        lock.withLock {
            withContext(Dispatchers.IO) {
                val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                val target = File(directory, "$name.json")
                val temp = File(directory, "$name.tmp")
                temp.writeText(obj("key" to str(key), "value" to value).toString())
                if (!temp.renameTo(target)) { target.writeText(temp.readText()); temp.delete() }
            }
        }
    }
}
