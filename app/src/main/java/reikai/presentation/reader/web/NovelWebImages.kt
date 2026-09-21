package reikai.presentation.reader.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import reikai.data.coil.NovelImage
import reikai.novel.content.NovelImageSources
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes a chapter's online pictures through the app, so WebView mode fetches them with the source's own
 * client and headers, as the text renderer does. Each address is rewritten to one on the host Android
 * reserves for an app to answer itself, which the reader's `shouldInterceptRequest` serves; nothing
 * else the page loads is touched. The source rides in the address because a merged novel's chapters
 * can come from different sources. One per viewport: only an address it produced is served, since a
 * chapter could otherwise name one (in a style or a script) that sends a source's headers anywhere.
 */
class NovelWebImages {

    private val routed = ConcurrentHashMap.newKeySet<NovelImage>()

    /** [html] with each online picture address rewritten; relative ones are resolved against [baseUrl]. */
    fun rewrite(html: String, baseUrl: String?, sourceId: String?): String {
        if (!html.contains("<img", ignoreCase = true) && !html.contains("<source", ignoreCase = true)) return html
        val document = Jsoup.parseBodyFragment(html)
        // Pretty-printing reflows preformatted text, the trap the download inliner avoids the same way.
        document.outputSettings().prettyPrint(false)
        document.select("img[src]").forEach { img ->
            routed(absolute(baseUrl, img.attr("src")), sourceId)?.let { img.attr("src", it) }
        }
        document.select("img[srcset], source[srcset]").forEach { element ->
            val candidates = NovelImageSources.parseSrcset(element.attr("srcset")).map { (url, descriptor) ->
                val address = routed(absolute(baseUrl, url), sourceId) ?: url
                if (descriptor.isEmpty()) address else "$address $descriptor"
            }
            element.attr("srcset", candidates.joinToString(", "))
        }
        return document.body().html()
    }

    /** The picture an address from [rewrite] stands for, or null for any other address. */
    fun imageFor(url: String): NovelImage? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.host != HOST || parsed.pathSegments != listOf(PATH)) return null
        val image = parsed.queryParameter("u") ?: return null
        return NovelImage(image, parsed.queryParameter("s")).takeIf { it in routed }
    }

    private fun routed(url: String, sourceId: String?): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        routed += NovelImage(url, sourceId)
        return HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegment(PATH)
            .addQueryParameter("u", url)
            .apply { sourceId?.let { addQueryParameter("s", it) } }
            .build()
            .toString()
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
        const val PATH = "rk-image"
    }

    /** A protocol-relative address is https, as the text renderer takes it; no base leaves the rest. */
    private fun absolute(baseUrl: String?, url: String): String {
        val trimmed = url.trim()
        baseUrl?.toHttpUrlOrNull()?.resolve(trimmed)?.let { return it.toString() }
        return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    }
}
