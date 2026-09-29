package reikai.domain.source

import java.net.URI

/**
 * A web address shared into the app, reduced to what matching it against a source's site needs. The query
 * and fragment are dropped, as LNReader's share handler drops them, so a `?utm` tag cannot split one entry
 * into two paths. Both content types read a link through these rules: docs/dev/plans/content-layer-sources-surface.md.
 */
class SharedLink private constructor(
    private val scheme: String,
    private val authority: String,
    val host: String,
    /** The raw path, `/` when empty. */
    private val path: String,
) {

    val url: String get() = "$scheme://$authority$path"

    /**
     * The path this link has below [site], or null when [site] is on another host or the link is not under
     * its path at a `/` boundary, or names the site itself.
     */
    fun relativeTo(site: String): String? {
        if (siteHost(site) != host) return null
        val sitePath = runCatching { URI(site.trim()).rawPath }.getOrNull().orEmpty().trimEnd('/')
        if (!path.startsWith("$sitePath/")) return null
        return path.substring(sitePath.length + 1).takeIf { it.trim('/').isNotEmpty() }
    }

    /** The spellings a source might have stored or parse this link as, most likely first. */
    fun candidates(site: String): List<String> {
        val relative = relativeTo(site) ?: return emptyList()
        return listOf(relative, "/$relative", url)
    }

    /** [candidates] plus each with its trailing slash toggled, the spellings a stored row may carry. */
    fun storedSpellings(site: String): List<String> = candidates(site).flatMap { listOf(it, it.toggledSlash()) }

    /** Whether a source's own [webUrl] for a path names this link. */
    fun isNamedBy(webUrl: String): Boolean = normalized(webUrl)?.let { it == normalized(url) } == true

    /** The [candidates] whose address, by the source's own [webUrl] rule, names this link. */
    suspend fun named(candidates: List<String>, webUrl: suspend (String) -> String): List<String> =
        candidates.filter { isNamedBy(webUrl(it)) }

    /** The one result a source's search found for this link, when its address names the link. */
    suspend fun <T> soleResult(results: List<T>, webUrl: suspend (T) -> String): T? =
        results.singleOrNull()?.takeIf { isNamedBy(webUrl(it)) }

    companion object {

        /** The link in [text], or null when it is not one http(s) address with a host. */
        fun parse(text: String): SharedLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = siteHost(uri.toString())?.takeIf { it.isNotEmpty() } ?: return null
            return SharedLink(scheme, uri.rawAuthority, host, uri.rawPath?.ifEmpty { "/" } ?: "/")
        }

        /**
         * How the source itself spells the entry [named] holds, or null when nothing it returned settles
         * it. A site ending in `/` joins `x` and `/x` to one page, so both name the link; the leading slash
         * the source's own [sourcePaths] carry picks the one its browse would have stored.
         */
        fun spelling(named: List<String>, sourcePaths: List<String>): String? {
            val relative = named.filterNot { it.isAbsolute() }
            if (relative.size <= 1) return relative.singleOrNull() ?: named.firstOrNull()
            val own = sourcePaths.filterNot { it.isAbsolute() }.ifEmpty { return null }
            val leading = when {
                own.all { it.startsWith("/") } -> true
                own.none { it.startsWith("/") } -> false
                else -> return null
            }
            return relative.singleOrNull { it.startsWith("/") == leading }
        }

        /**
         * The address one level above [path], or null at the top. A source that reads its entry from one path
         * segment (WuxiaWorld's plugin) parses a chapter address as the whole entry, so a guess whose parent
         * parses to the same entry was a link below one.
         */
        fun parentOf(path: String): String? =
            path.trimEnd('/').substringBeforeLast('/', "").takeIf { it.trim('/').isNotEmpty() && !it.endsWith(":/") }

        /**
         * One match across every source that serves a link: a stored row wins, and failing one, a guess
         * counts only when no other source guessed too, so the app never picks between two at random.
         */
        fun <T> single(matches: List<T>, isStored: (T) -> Boolean): T? =
            matches.filter(isStored).singleOrNull() ?: matches.singleOrNull()

        // Host without `www.`, any scheme, one trailing slash dropped, and a doubled slash after the host
        // read as one, since a site ending in `/` joined to a path starting with one is the same page.
        private fun normalized(url: String): String? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val host = siteHost(url) ?: return null
            val path = uri.rawPath.orEmpty().replaceFirst(LEADING_SLASHES, "/").removeSuffix("/")
            return host + path + (uri.rawQuery?.let { "?$it" } ?: "")
        }

        private val LEADING_SLASHES = Regex("^/+")

        private fun String.isAbsolute() = startsWith("http://") || startsWith("https://")

        private fun String.toggledSlash() = if (endsWith("/")) removeSuffix("/") else "$this/"
    }
}
