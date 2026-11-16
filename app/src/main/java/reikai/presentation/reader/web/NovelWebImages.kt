package reikai.presentation.reader.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import reikai.data.coil.NovelImage
import reikai.data.coil.isNovelImageAddress
import reikai.novel.content.NovelChapterAddress
import reikai.novel.content.NovelImageSources
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes a chapter's pictures through the app, so WebView mode fetches them as the text renderer does: an
 * online one with the source's client and headers, a local novel's from its book, which the page has no
 * file access to read. Each address is rewritten onto the host Android reserves for an app to answer, which
 * `shouldInterceptRequest` serves; nothing else is touched. The source rides in the address, since a merged
 * novel's chapters can come from different sources. One per viewport: only an address it produced is
 * served, since a chapter could otherwise name one (in a style or a script) that sends headers anywhere.
 */
class NovelWebImages {

    private val routed = ConcurrentHashMap.newKeySet<NovelImage>()

    /** [html] with each fetched picture's address rewritten; relative ones are resolved against [baseUrl]. */
    fun rewrite(html: String, baseUrl: String?, sourceId: String?): String {
        if (!html.contains("<img", ignoreCase = true) && !html.contains("<source", ignoreCase = true)) return html
        val document = Jsoup.parseBodyFragment(html)
        // Pretty-printing reflows preformatted text, the trap the download inliner avoids the same way.
        document.outputSettings().prettyPrint(false)
        // The page waits on every picture before it seeks or reports a page ready, and a lazy one past the
        // fold is never fetched, so the wait would always run out its cap. The text renderer loads all too.
        document.select("img[loading]").removeAttr("loading")
        document.select("img[src]").forEach { img ->
            routed(NovelChapterAddress.absolute(baseUrl, img.attr("src")), sourceId)?.let { img.attr("src", it) }
        }
        document.select("img[srcset], source[srcset]").forEach { element ->
            val candidates = NovelImageSources.parseSrcset(element.attr("srcset")).map { (url, descriptor) ->
                val address = routed(NovelChapterAddress.absolute(baseUrl, url), sourceId) ?: url
                if (descriptor.isEmpty()) address else "$address $descriptor"
            }
            element.attr("srcset", candidates.joinToString(", "))
        }
        return document.body().html()
    }

    /** The picture an address from [rewrite] stands for, or null for any other address. */
    fun imageFor(url: String): NovelImage? {
        val parsed = appAddressAt(url, PATH) ?: return null
        val image = parsed.queryParameter("u") ?: return null
        return NovelImage(image, parsed.queryParameter("s")).takeIf { it in routed }
    }

    /** Whether an address from [rewrite] is a Retry's, which the page marks so the picture is fetched afresh. */
    fun isRetry(url: String): Boolean = appAddressAt(url, PATH)?.queryParameter(RETRY) != null

    private fun routed(url: String, sourceId: String?): String? {
        if (!isNovelImageAddress(url)) return null
        routed += NovelImage(url, sourceId)
        return appAddress(PATH)
            .addQueryParameter("u", url)
            .apply { sourceId?.let { addQueryParameter("s", it) } }
            .build()
            .toString()
    }

    companion object {
        /** The host Android reserves for an app to answer itself, which the reader's fonts use too. */
        const val HOST = "appassets.androidplatform.net"
        private const val PATH = "rk-image"

        // reader.js adds it on Retry; the cache may hold the bytes that failed, so a retry reads past it.
        internal const val RETRY = "retry"

        /** What every [rewrite] address starts with, which reader.js receives to tell a picture of ours. */
        internal val PAGE_ROUTE: String = appAddress(PATH).build().toString() + "?"

        /** An address on [HOST] at [path], which the reader's `shouldInterceptRequest` answers. */
        internal fun appAddress(path: String): HttpUrl.Builder =
            HttpUrl.Builder().scheme("https").host(HOST).addPathSegment(path)

        /** [url] when it is an [appAddress] at [path], else null. */
        internal fun appAddressAt(url: String, path: String): HttpUrl? =
            url.toHttpUrlOrNull()?.takeIf { it.host == HOST && it.pathSegments == listOf(path) }
    }
}
