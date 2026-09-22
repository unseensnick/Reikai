package eu.kanade.tachiyomi.data.track.novelupdates

import org.jsoup.nodes.Document

/** One release of a series on the site: a translation group's post of one chapter, ticked by [id]. */
data class NovelUpdatesRelease(val id: String, val name: String)

private val RELEASE_LINK = Regex("""(?:^|novelupdates\.com)/?extnu/(\d+)""")

private val SERIES_SLUG = Regex("""series/([^/?#]+)""")

/**
 * The release id in a chapter link, when the link is one of the site's own release links: absolute
 * from the extension, relative from the LNReader plugin. Any other source's link answers null.
 */
internal fun releaseIdOf(url: String): String? = RELEASE_LINK.find(url)?.groupValues?.get(1)

/** The series slug in a series link, or [url] itself when it is already a bare slug. */
internal fun seriesSlugOf(url: String): String? =
    SERIES_SLUG.find(url)?.groupValues?.get(1)
        ?: url.trim('/').takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' } }

/** The `nd_getchapters` answer: each release is the link after the group's in its row. */
internal fun parseReleases(document: Document): List<NovelUpdatesRelease> =
    document.select("li.sp_li_chp").mapNotNull { row ->
        val link = row.select("a").first()?.nextElementSibling() ?: return@mapNotNull null
        val id = releaseIdOf(link.absUrl("href").ifEmpty { link.attr("href") }) ?: return@mapNotNull null
        NovelUpdatesRelease(id, row.text())
    }

/**
 * Which release to tick for chapter [number]: the one the read chapter links to when that is a single
 * release, as the extension ticks; else the site's only release numbered [number], when [releases]
 * can be fetched; else none, since ticking a guess marks the wrong group's post.
 */
internal suspend fun pickRelease(
    number: Double,
    readReleaseIds: Set<String>,
    releases: suspend () -> List<NovelUpdatesRelease>,
    numberOf: (String) -> Double,
): String? {
    readReleaseIds.singleOrNull()?.let { return it }
    // An unnumbered chapter matches the site's every unnumbered release, a prologue or a side story.
    if (readReleaseIds.isNotEmpty() || number <= 0) return null
    return releases().filter { numberOf(it.name) == number }.singleOrNull()?.id
}

/**
 * Whether a read of chapter [chapter] must leave the site where it is: only an automatic read, with
 * the setting on, of a chapter below the progress the site already has.
 */
internal fun holdsBack(isRead: Boolean, neverBackwards: Boolean, chapter: Double, onSite: Int?): Boolean =
    isRead && neverBackwards && onSite != null && chapter < onSite

/**
 * The site's progress after unreading [unreadChapter]: the highest chapter still read, never above what
 * the site had. Null when the unread is above the site's progress, which was never marked there.
 */
internal fun progressAfterUnread(unreadChapter: Double, stillRead: Double?, onSite: Int?): Double? {
    if (onSite != null && unreadChapter > onSite) return null
    val left = stillRead ?: 0.0
    return if (onSite != null) minOf(left, onSite.toDouble()) else left
}

/** The release an unread unticks: the lowest chapter unread, as the extension does. */
internal fun <T> unreadTarget(unread: List<T>, numberOf: (T) -> Double): T? =
    unread.filter { numberOf(it) > 0 }.minByOrNull(numberOf) ?: unread.firstOrNull()
