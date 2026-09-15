package reikai.novel.host

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Downloads compiled plugin `.js` files and keeps the installed ones under
 * `context.filesDir/lnplugins/<sha256(url)>.js`. A plugin URL always serves the repo's latest script,
 * so loading reads only the stored file and an installed plugin changes version only through [store].
 */
@Inject
@SingleIn(AppScope::class)
class LnPluginLoader(
    private val context: Context,
    private val networkHelper: NetworkHelper,
) {
    /** The script [url] serves now. Stores nothing: the caller decides whether it becomes installed. */
    suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        logcat(LogPriority.INFO) { "downloading plugin from $url" }
        val req = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()
        networkHelper.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw LnPluginException("plugin download failed: HTTP ${res.code} from $url")
            }
            val source = res.body.string()
            if (source.isBlank()) throw LnPluginException("plugin download returned empty body from $url")
            source
        }
    }

    /** The installed script for [url], or null when there is none to load. */
    suspend fun installed(url: String): String? = withContext(Dispatchers.IO) {
        val file = fileFor(url)
        if (!file.exists()) adoptCachedScript(file)
        if (!file.exists()) return@withContext null
        val script = file.readText()
        // Written whole since scripts left the cache folder, but one adopted from there can be a partial
        // download. The compiled plugin always carries its default export, so without it nothing loads.
        if (!script.contains(PLUGIN_EXPORT_MARKER)) {
            logcat(LogPriority.WARN) { "installed plugin ${file.name} looks truncated for $url" }
            return@withContext null
        }
        script
    }

    /** Makes [script] the installed one for [url], replacing the file whole so a crash can't leave half. */
    suspend fun store(url: String, script: String): Unit = withContext(Dispatchers.IO) {
        val file = fileFor(url)
        val partial = File(file.parentFile, "${file.name}.tmp")
        partial.writeText(script)
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    suspend fun delete(url: String) = withContext(Dispatchers.IO) {
        fileFor(url).delete()
    }

    /** Scripts used to live in the cache folder, where Android could clear them and force a download. */
    private fun adoptCachedScript(file: File) {
        val cached = File(File(context.cacheDir, DIR_NAME), file.name)
        if (!cached.exists()) return
        if (!cached.renameTo(file)) {
            cached.copyTo(file, overwrite = true)
            cached.delete()
        }
    }

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, "$hash.js")
    }

    companion object {
        private const val DIR_NAME = "lnplugins"
        private const val PLUGIN_EXPORT_MARKER = "exports.default"
    }
}
