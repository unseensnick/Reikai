package reikai.presentation.reader.web

import android.content.Context
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import reikai.novel.font.NovelFontManager
import reikai.novel.font.ReaderFontSource
import reikai.novel.font.readerFontSource
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The chosen face, resolved the way the native renderer resolves it in `NovelTextStyle` (a user's file
 * through the font manager, anything else as a bundled asset) and served to the page from an address on
 * the host the chapter's pictures use ([NovelWebImages]), since this mode keeps file access off. Inlined
 * as a data URI it was most of the document and took about 100 ms to encode on a slow phone at every open.
 * One per viewport, and only an address it handed out is answered.
 */
class NovelWebFonts {

    private class Face(val source: ReaderFontSource, val file: File?, val type: String)

    private val served = ConcurrentHashMap<String, Face>()

    /** Where the page loads [family]'s face from, or null for a generic family or a missing file. */
    suspend fun url(fonts: NovelFontManager, family: String): String? {
        val source = readerFontSource(family)
        if (source == ReaderFontSource.SourceDefault || source is ReaderFontSource.Generic) return null
        return withContext(Dispatchers.IO) {
            val file = if (source is ReaderFontSource.UserFile) {
                // Copies the file out of the user's storage folder on first use, which is why this is off main.
                fonts.localFile(source.fileName) ?: return@withContext null
            } else {
                null
            }
            val type = if (family.endsWith(".otf", ignoreCase = true)) "font/otf" else "font/ttf"
            NovelWebImages.appAddress(PATH)
                .addQueryParameter("f", family)
                // A replaced file gets a new address, so the WebView never answers it from its cache.
                .apply { file?.let { addQueryParameter("v", "${it.length()}-${it.lastModified()}") } }
                .build()
                .toString()
                .also { served[it] = Face(source, file, type) }
        }
    }

    /** The face behind an address from [url], read on the WebView's worker thread; null for any other address. */
    fun response(context: Context, url: String): WebResourceResponse? {
        NovelWebImages.appAddressAt(url, PATH) ?: return null
        val face = served[url] ?: return null
        val bytes = runCatching {
            when (val source = face.source) {
                is ReaderFontSource.UserFile -> face.file?.readBytes()
                is ReaderFontSource.Bundled -> context.assets.open(source.assetPath).use { it.readBytes() }
                ReaderFontSource.SourceDefault, is ReaderFontSource.Generic -> null
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "Failed to read a reader font" } }.getOrNull()
            ?: return WebResourceResponse(null, null, 404, "Font unavailable", CORS, ByteArrayInputStream(ByteArray(0)))
        return WebResourceResponse(face.type, null, 200, "OK", CORS, ByteArrayInputStream(bytes))
    }

    private companion object {
        const val PATH = "rk-font"

        // The page's origin is the chapter's site, and a face from another origin needs this to load.
        val CORS = mapOf("Access-Control-Allow-Origin" to "*")
    }
}
