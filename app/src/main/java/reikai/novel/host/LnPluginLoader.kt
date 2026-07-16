package reikai.novel.host

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest

/**
 * Downloads a compiled plugin `.js` from an arbitrary URL and caches it under
 * `context.cacheDir/lnplugins/<sha256(url)>.js`. Cache invalidation is manual: delete the file
 * or call [clearCache] to force a re-fetch.
 */
class LnPluginLoader(
    private val context: Context,
    private val client: OkHttpClient,
) {
    suspend fun fetchSource(url: String, forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val file = cacheFileFor(url)
        if (!forceRefresh && file.exists() && file.length() > 0) {
            val cached = file.readText()
            // A non-empty but truncated cache file (a partial write or an interrupted earlier download)
            // would load as a broken plugin. The compiled plugin always contains the LNReader default
            // export, so its absence means the cached copy is incomplete: fall through and re-download.
            // Only the cache is gated this way; a fresh download is trusted, so a future build-format
            // change can't send this into a re-download loop.
            if (cached.contains(PLUGIN_EXPORT_MARKER)) {
                logcat(LogPriority.INFO) { "plugin cache hit ${file.name} for $url" }
                return@withContext cached
            }
            logcat(LogPriority.WARN) { "cached plugin ${file.name} looks truncated, re-downloading $url" }
        }
        logcat(LogPriority.INFO) { "downloading plugin from $url" }
        val req = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw LnPluginException("plugin download failed: HTTP ${res.code} from $url")
            }
            val source = res.body.string()
            if (source.isBlank()) throw LnPluginException("plugin download returned empty body from $url")
            file.parentFile?.mkdirs()
            file.writeText(source)
            source
        }
    }

    fun clearCache() {
        cacheDir().listFiles()?.forEach { it.delete() }
    }

    private fun cacheDir(): File = File(context.cacheDir, "lnplugins").apply { mkdirs() }

    private fun cacheFileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(cacheDir(), "$hash.js")
    }

    companion object {
        // The compiled LNReader plugin's default export; its presence is a cheap completeness check for
        // a cached file (see fetchSource).
        private const val PLUGIN_EXPORT_MARKER = "exports.default"
    }
}
