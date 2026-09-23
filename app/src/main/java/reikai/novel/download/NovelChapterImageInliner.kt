package reikai.novel.download

import android.util.Base64
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.internal.StringUtil
import org.jsoup.nodes.Element
import reikai.novel.content.NovelImageSources
import reikai.novel.network.NovelImageClient

/**
 * Inlines a downloaded chapter's images as `data:` URIs so the saved HTML is self-contained and reads
 * offline without file access. Base64 into the HTML rather than LNReader's separate files plus a
 * `file://` src: the reader loads chapter HTML through `loadDataWithBaseURL` with a remote or null
 * base, so `file://` images would be cross-origin-blocked unless the WebView enabled
 * `setAllowFileAccessFromFileURLs`, which the security rules forbid. Chapters are mostly text, so the
 * roughly 33% inflation is negligible; a per-image failure leaves the original `src`.
 */
private const val MAX_INLINE_BYTES = 5L * 1024 * 1024

suspend fun inlineChapterImages(html: String, baseSite: String, images: NovelImageClient): String {
    val document = Jsoup.parse(html, baseSite)
    if (document.select("img, picture").isEmpty()) return html
    // Pretty-printing reflows the markup, which folds the line breaks inside a paragraph the source
    // styles as preformatted; only the image sources are meant to change here. Same trap as the
    // sanitiser's, in NovelHtmlUtils.
    document.outputSettings().prettyPrint(false)
    // Both readers prefer a srcset over src, so a stored image keeps one source: the one inlined.
    NovelImageSources.unwrapPictures(document)

    for (img in document.select("img")) {
        // With no src, the widest candidate, since a stored copy is read at whatever width the device has.
        // A data: src beside a srcset is a lazy-load placeholder, and the srcset holds the picture.
        val widest = NovelImageSources.srcsetCandidate(img, Int.MAX_VALUE).orEmpty()
        val given = img.attr("src")
        val src = if (given.isBlank() || given.startsWith("data:")) widest else given
        if (src.isBlank()) continue
        val absolute = StringUtil.resolve(img.baseUri(), src).ifBlank { src }
        runCatching {
            val picture = images.forUrl(absolute)
            val request = Request.Builder().url(absolute).headers(picture.headers).build()
            picture.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val body = response.body
                // Bound the read itself, not just the post-read size: a lying or unknown (-1)
                // content-length would otherwise let body.bytes() pull an arbitrarily large image
                // fully into memory before any size check could reject it.
                if (body.contentLength() > MAX_INLINE_BYTES) return@use
                val source = body.source()
                source.request(MAX_INLINE_BYTES + 1)
                if (source.buffer.size > MAX_INLINE_BYTES) return@use
                val bytes = source.readByteArray()
                if (bytes.isEmpty()) return@use
                val mime = response.header("Content-Type")?.substringBefore(';')?.takeIf { it.startsWith("image/") }
                    ?: "image/jpeg"
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                img.attr("src", "data:$mime;base64,$encoded")
                dropCandidates(img)
            }
        }
    }
    return document.body().html()
}

private fun dropCandidates(img: Element) {
    img.removeAttr("srcset")
    img.removeAttr("sizes")
}
