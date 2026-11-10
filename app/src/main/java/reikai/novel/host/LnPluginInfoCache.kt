package reikai.novel.host

import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.serialization.Serializable
import java.io.File

/**
 * What an installed plugin said about itself, one file per plugin id, so a cold start reads it back instead
 * of building an engine and evaluating the whole runtime for each plugin. Keyed on everything that read
 * saw: the runtime scripts, the plugin's script and its stored settings (a plugin may take its site from
 * one), so any change reads the plugin again. Disk I/O: call it off Main.
 */
internal class LnPluginInfoCache(private val dir: File) {

    fun get(pluginId: String, key: String): LnPluginInfo? = runCatching {
        val file = fileFor(pluginId).takeIf(File::exists) ?: return null
        val entry = LnPluginHost.JSON.decodeFromString(Entry.serializer(), file.readText())
        entry.info.takeIf { entry.key == key }
    }.getOrNull()

    fun put(pluginId: String, key: String, info: LnPluginInfo) {
        dir.mkdirs()
        fileFor(pluginId).writeText(LnPluginHost.JSON.encodeToString(Entry.serializer(), Entry(key, info)))
    }

    private fun fileFor(pluginId: String) = File(dir, "${Hash.sha256(pluginId).take(32)}.json")

    @Serializable
    private data class Entry(val key: String, val info: LnPluginInfo)

    companion object {
        /** [runtimeHash] is the runtime scripts' own digest, taken once rather than per plugin. */
        fun key(runtimeHash: String, source: String, storage: String): String =
            Hash.sha256(listOf(runtimeHash, source, storage).joinToString("\u0000"))
    }
}
