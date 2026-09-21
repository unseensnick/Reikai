package reikai.presentation.reader

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import coil3.fetch.SourceFetchResult
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import reikai.data.coil.NovelImage
import reikai.presentation.reader.web.NovelWebImages
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream

/**
 * Keeps a chapter document on the page it was loaded as.
 *
 * The reader WebView carries the native bridge and the app's shared cookie jar, so a foreign page
 * loaded into it inherits both. Nothing in the pipeline strips anchors. A tapped link opens in the
 * browser; every other navigation is refused. It also serves the chapter's pictures, which
 * [NovelWebImages] routes here.
 */
class NovelChapterNavigationClient(
    private val context: Context,
    /** The document's own URL, as handed to `loadDataWithBaseURL`. */
    private val baseUrl: () -> String?,
    /** The pictures this document's chapters were routed through. */
    private val images: NovelWebImages,
    private val fetchImage: suspend (NovelImage) -> SourceFetchResult,
) : WebViewClient() {

    /** Called on a WebView worker thread, which is why it may block. A failure is an error status, which
     *  the page draws as a failed picture with Retry. */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val image = images.imageFor(request.url.toString()) ?: return null
        return try {
            val result = runBlocking { fetchImage(image) }
            val bytes = result.source.use { it.source().readByteArray() }
            // Chromium sniffs a picture's format, so a type the disk cache did not keep costs nothing.
            WebResourceResponse(result.mimeType ?: "image/*", null, ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG, e) { "Failed to load a chapter image" }
            // Only an error status is accepted here; a redirect or an unknown code throws.
            val code = (e as? HttpException)?.code?.takeIf { it in 400..599 } ?: 502
            WebResourceResponse(null, null, code, "Image unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return when (decide(request.url.toString(), baseUrl(), request.hasGesture())) {
            Decision.ALLOW -> false
            Decision.BLOCK -> true
            Decision.OPEN_EXTERNALLY -> {
                context.openInBrowser(Uri.parse(request.url.toString()))
                true
            }
        }
    }

    enum class Decision { ALLOW, BLOCK, OPEN_EXTERNALLY }

    companion object {

        /**
         * `loadDataWithBaseURL` makes [baseUrl] the document's own URL, so a link to it plus a fragment
         * is the chapter jumping within itself. The URL alone is not: a link to it (an empty `href`
         * resolves there) loads the live page from the source's site, bridge and cookies included. A
         * null base means the document has no origin worth trusting, and then nothing is same-document.
         */
        fun decide(requestUrl: String, baseUrl: String?, hasGesture: Boolean): Decision {
            if (baseUrl != null && requestUrl.startsWith("$baseUrl#")) return Decision.ALLOW
            if (requestUrl == baseUrl) return Decision.BLOCK
            val isWeb = requestUrl.startsWith("http://") || requestUrl.startsWith("https://")
            // Without a gesture the page is navigating itself, which a chapter has no reason to do.
            return if (hasGesture && isWeb) Decision.OPEN_EXTERNALLY else Decision.BLOCK
        }
    }
}
