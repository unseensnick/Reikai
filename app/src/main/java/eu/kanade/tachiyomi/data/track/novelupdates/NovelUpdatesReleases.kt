package eu.kanade.tachiyomi.data.track.novelupdates

import org.jsoup.nodes.Document

/** One release of a series on the site: a translation group's post of one chapter, bookmarked by [id]. */
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
 * Which release to bookmark for chapter [number]: the one the read chapter links to when that is a single
 * release, as the extension marks; else the site's only release numbered [number], which [releases]
 * answers given the match; else none, since bookmarking a guess marks the wrong group's post.
 */
internal suspend fun pickRelease(
    number: Double,
    readReleaseIds: Set<String>,
    releases: suspend (matches: (NovelUpdatesRelease) -> Boolean) -> List<NovelUpdatesRelease>,
    numberOf: (String) -> Double,
): String? {
    readReleaseIds.singleOrNull()?.let { return it }
    // An unnumbered chapter matches the site's every unnumbered release, a prologue or a side story.
    if (readReleaseIds.isNotEmpty() || number <= 0) return null
    return releases { numberOf(it.name) == number }.singleOrNull()?.id
}

/**
 * The last series' release list, held so a run of reads asks the site for it once. Releases are only
 * ever added, so a held list goes stale only by what it lacks: a pick it holds no release for asks again.
 */
internal class HeldReleases(private val fetch: suspend (novelId: String) -> List<NovelUpdatesRelease>) {
    @Volatile
    private var held: Pair<String, List<NovelUpdatesRelease>>? = null

    /** The series' releases [matches] accepts: the held list's when it has any, else a fresh list's. */
    suspend fun matching(novelId: String, matches: (NovelUpdatesRelease) -> Boolean): List<NovelUpdatesRelease> {
        held?.takeIf { it.first == novelId }?.second?.filter(matches)?.ifEmpty { null }?.let { return it }
        val fresh = fetch(novelId)
        held = novelId to fresh
        return fresh.filter(matches)
    }
}

/**
 * Whether a read of chapter [chapter] must leave the site where it is: only an automatic read, with
 * the setting on, of a chapter below the progress the site already has.
 */
internal fun holdsBack(isRead: Boolean, neverBackwards: Boolean, chapter: Double, onSite: Int?): Boolean =
    isRead && neverBackwards && onSite != null && chapter < onSite

/**
 * The site's progress after unreading the chapters numbered [unread]: the highest chapter still read,
 * never above what the site had. Null, leaving the site alone, when the unread moved nothing it could
 * carry: no numbered chapter (a prologue has no position), the lowest one above the site's progress
 * (never marked there), or the highest one still below [stillRead] (the app's own progress is unchanged,
 * and the site may be ahead from reading elsewhere).
 */
internal fun progressAfterUnread(unread: List<Double>, stillRead: Double?, onSite: Int?): Double? {
    val numbered = unread.filter { it > 0 }.ifEmpty { return null }
    if (onSite != null && numbered.min() > onSite) return null
    if (stillRead != null && stillRead >= numbered.max()) return null
    val left = stillRead ?: 0.0
    return if (onSite != null) minOf(left, onSite.toDouble()) else left
}

/** What binding does on the site: file a series on no list yet, or keep the list the user already chose. */
internal sealed interface BindOnSite {
    /** On none of the user's lists: filed under [status], with the progress written into the note. */
    data class File(val status: Long) : BindOnSite

    /** Already on a list: the site's list and note are taken and never written lower; [moveTo] is the one move. */
    data class Keep(val moveTo: Long?) : BindOnSite
}

/**
 * How a bind treats the site, given the status of the list the series is on ([siteStatus], null for
 * none). A kept series moves from Plan to read to Reading once chapters are read, as the list trackers
 * do, and otherwise stays where the user put it, a list of their own included.
 */
internal fun bindOnSite(siteStatus: Long?, hasReadChapters: Boolean): BindOnSite = when {
    siteStatus == null -> BindOnSite.File(if (hasReadChapters) NovelUpdates.READING else NovelUpdates.PLAN_TO_READ)
    hasReadChapters && siteStatus == NovelUpdates.PLAN_TO_READ -> BindOnSite.Keep(NovelUpdates.READING)
    else -> BindOnSite.Keep(moveTo = null)
}

/** A read files the series under Reading, except a Completed one or one on a list of the user's own. */
internal fun statusAfterRead(status: Long): Long =
    if (status == NovelUpdates.COMPLETED || status == NovelUpdates.OTHER_LIST) status else NovelUpdates.READING
