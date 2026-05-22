package yokai.novel.host

import android.content.Context
import co.touchlab.kermit.Logger
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a compiled plugin `.js` from an arbitrary URL and caches it under
 * `context.cacheDir/lnplugins/<sha256(url)>.js`. Cache invalidation is manual for the spike:
 * delete the file or call [clearCache] to force a re-fetch.
 */
class LnPluginLoader(
    private val context: Context,
    private val client: OkHttpClient,
) {
    suspend fun fetchSource(url: String, forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val file = cacheFileFor(url)
        if (!forceRefresh && file.exists() && file.length() > 0) {
            Logger.i(LN_HOST_TAG) { "plugin cache hit ${file.name} for $url" }
            return@withContext file.readText()
        }
        Logger.i(LN_HOST_TAG) { "downloading plugin from $url" }
        val req = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw LnPluginException("plugin download failed: HTTP ${res.code} from $url")
            }
            val source = res.body?.string().orEmpty()
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
}
