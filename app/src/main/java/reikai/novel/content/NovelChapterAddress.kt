package reikai.novel.content

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a chapter's picture or link points, by the rules a browser reading the chapter applies, so both
 * readers and the download fetch and open the same address. reader.js resolves a scrolled-in chapter's
 * links with the browser's own parser, which this matches.
 */
object NovelChapterAddress {

    /**
     * The plugin controls the site, and a file:// base would hand the chapter document a file origin.
     * Spelled as WebView reports the document's URL back, so the link policy can recognise it.
     */
    fun trustedBase(baseUrl: String?): String? = baseUrl?.toHttpUrlOrNull()?.toString()

    /**
     * [address] against [baseUrl] when that is a web address. A protocol-relative address with no base
     * is https. A blank address, a fragment (a jump within the chapter) and any other scheme stay as given.
     */
    fun absolute(baseUrl: String?, address: String): String {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return trimmed
        val resolved = baseUrl?.toHttpUrlOrNull()?.resolve(trimmed)
            ?: trimmed.toHttpUrlOrNull()
            ?: "https:$trimmed".takeIf { trimmed.startsWith("//") }?.toHttpUrlOrNull()
        return resolved?.toString() ?: trimmed
    }

    fun isWebAddress(url: String): Boolean = url.toHttpUrlOrNull() != null
}
