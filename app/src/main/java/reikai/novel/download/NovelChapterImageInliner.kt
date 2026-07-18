package reikai.novel.download

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Inlines a downloaded chapter's inline images as `data:` URIs so the saved HTML is fully
 * self-contained and reads offline without file access.
 *
 * We embed (Base64 into the HTML) rather than save images as separate files + rewrite `src` to
 * `file://` (LNReader's scheme): the reader loads chapter HTML via `loadDataWithBaseURL` with a
 * remote/null base, so `file://` images would be cross-origin-blocked unless the WebView enabled
 * `setAllowFileAccessFromFileURLs` (forbidden by our security rules). A `data:` URI sidesteps that and
 * keeps the one-file-per-chapter provider scheme. Light-novel chapters are mostly text, so the ~33%
 * Base64 inflation is negligible; per-image failures leave the original `src` (online fallback).
 */
private const val MAX_INLINE_BYTES = 5L * 1024 * 1024

suspend fun inlineChapterImages(html: String, baseSite: String, client: OkHttpClient): String {
    val document = Jsoup.parse(html, baseSite)
    val images = document.select("img")
    if (images.isEmpty()) return html

    for (img in images) {
        val src = img.attr("src")
        if (src.isBlank() || src.startsWith("data:")) continue
        val absolute = img.absUrl("src").ifBlank { src }
        runCatching {
            client.newCall(Request.Builder().url(absolute).build()).execute().use { response ->
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
            }
        }
    }
    return document.body().html()
}
