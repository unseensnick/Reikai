package eu.kanade.tachiyomi.data.track.novelupdates

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// Every parse NovelUpdates needs, kept apart from the requests so each one is testable against a
// fixture. Selectors sit in NuSelector so a site redesign has one place to fix rather than a hunt
// through the call sites, which is the whole maintenance cost of a scraped tracker.

/** What signing in reads back: the name for the tracker row, and the lists to map statuses onto. */
data class NovelUpdatesAccount(
    val username: String?,
    val lists: List<Pair<String, String>>,
)

/** What "Fill from tracker" reads off a series page. */
data class NovelUpdatesDetails(
    val title: String?,
    val coverUrl: String?,
    val description: String?,
    val authors: List<String>,
    val artists: List<String>,
    val genres: List<String>,
)

/** A search hit. [id] is the numeric post id when the row carries one, resolved at bind when not. */
data class NovelUpdatesSeries(
    val id: String?,
    val title: String,
    val seriesUrl: String,
    val coverUrl: String,
    val summary: String,
    val publishingStatus: String,
)

internal object NuSelector {
    const val SEARCH_ROW = "div.search_main_box_nu"
    const val RESULT_TITLE = "div.search_title a, .search_title a"
    const val RESULT_ID = "span[id^=sid]"
    const val RESULT_COVER = "div.search_img_nu img, .search_img_nu img"
    const val RESULT_BODY = "div.search_body_nu"
    const val RESULT_HIDDEN_TEXT = ".testhide"
    const val RESULT_GENRES = "div.search_genre, .search_genre"

    const val SHORTLINK = "link[rel=shortlink]"
    const val ACTIVITY_LINK = "a[href*=activity-stats]"
    const val POST_ID = "input#mypostid"

    const val LIST_PANEL = "div.sticon"
    const val ADD_ME = "img[src*=addme.png]"
    const val LIST_LINK = "span.sttitle a"
    const val LIST_MENU_ITEM = "div#cssmenu li a[href*=reading-list/?list=]"
    const val LIST_OPTION = "div.sticon select.stmove option"

    // The profile link carries `/user/<id>/<name>/`; the labels are the header's own copies, one
    // per layout, and `menu_username_right` is skipped because its text carries markup whitespace.
    const val PROFILE_LINK = "a[href*=/user/]"
    const val USERNAME_LABEL = "span.username_main, span.username"

    const val DETAILS_TITLE = ".seriestitlenu"
    const val DETAILS_COVER = ".serieseditimg img"
    const val DETAILS_DESCRIPTION = "#editdescription"
    const val DETAILS_AUTHORS = "#showauthors a"
    const val DETAILS_ARTISTS = "#showartists a"

    // Genres only. The sibling `#showtags` runs to eighty or more entries on a popular series, and
    // there is one field to put them in, so tags would bury the handful that describe the work.
    const val DETAILS_GENRES = "#seriesgenre a"
}

private val SHORTLINK_ID = Regex("""p=(\d+)""")
private val ACTIVITY_ID = Regex("""seriesid=(\d+)""")
private val LIST_ID = Regex("""list=(\d+)""")
private val SID = Regex("""sid(\d+)""")
private val WHITESPACE = Regex("""\s+""")
private val PROFILE_NAME = Regex("""/user/\d+/([^/]+)""")

// Where the CJK blocks begin: kana, Han and Hangul all sit above it, Latin and its accents below.
private const val CJK_BLOCK_START = 0x2E80

internal fun parseSearch(document: Document): List<NovelUpdatesSeries> =
    document.select(NuSelector.SEARCH_ROW).map { it.toSeries() }

/** The numeric post id, which search rows do not always carry. Null when the page shows none. */
internal fun parseNovelId(document: Document): String? {
    SHORTLINK_ID.find(document.select(NuSelector.SHORTLINK).attr("href"))
        ?.let { return it.groupValues[1] }
    ACTIVITY_ID.find(document.select(NuSelector.ACTIVITY_LINK).attr("href"))
        ?.let { return it.groupValues[1] }
    return document.select(NuSelector.POST_ID).attr("value").ifBlank { null }
}

/** The list the novel sits on, or null when it is on none, which the add button marks. */
internal fun parseListId(document: Document): Long? {
    val panel = document.select(NuSelector.LIST_PANEL)
    if (panel.select(NuSelector.ADD_ME).isNotEmpty()) return null
    return LIST_ID.find(panel.select(NuSelector.LIST_LINK).attr("href"))
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
}

/** The user's own lists as id to name. The menu is authoritative; the dropdown is the fallback. */
internal fun parseReadingLists(document: Document): List<Pair<String, String>> {
    val fromMenu = document.select(NuSelector.LIST_MENU_ITEM).mapNotNull { link ->
        val id = LIST_ID.find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
        id to (link.text().trim().ifBlank { return@mapNotNull null })
    }
    if (fromMenu.isNotEmpty()) return fromMenu

    return document.select(NuSelector.LIST_OPTION).mapNotNull { option ->
        val id = option.attr("value").takeIf { it.isNotBlank() && it != "---" } ?: return@mapNotNull null
        id to (option.text().trim().ifBlank { return@mapNotNull null })
    }
}

/**
 * The signed-in user's name, for the tracker row's subtitle. Read from the profile link first,
 * because a URL shape outlives a class name, with the header labels as the fallback.
 */
internal fun parseUsername(document: Document): String? {
    PROFILE_NAME.find(document.select(NuSelector.PROFILE_LINK).attr("href"))
        ?.let { return it.groupValues[1] }
    return document.select(NuSelector.USERNAME_LABEL)
        .firstOrNull()
        ?.text()
        ?.trim()
        ?.ifBlank { null }
}

/** What "Fill from tracker" takes off a series page. */
internal fun parseDetails(document: Document): NovelUpdatesDetails = NovelUpdatesDetails(
    title = document.selectFirst(NuSelector.DETAILS_TITLE)?.text()?.trim()?.ifBlank { null },
    coverUrl = document.selectFirst(NuSelector.DETAILS_COVER)?.attr("src")?.ifBlank { null },
    description = document.selectFirst(NuSelector.DETAILS_DESCRIPTION)?.text()?.trim()?.ifBlank { null },
    authors = preferLatinNames(document.select(NuSelector.DETAILS_AUTHORS).map { it.text().trim() }),
    artists = preferLatinNames(document.select(NuSelector.DETAILS_ARTISTS).map { it.text().trim() }),
    genres = document.select(NuSelector.DETAILS_GENRES).map { it.text().trim() }.filter { it.isNotEmpty() },
)

/**
 * NovelUpdates credits each person twice, romanized then in their own script, so a raw join reads as
 * twice as many people as there are. Keeping the Latin-script entries drops the duplicates, and an
 * entry that was never romanized keeps everything rather than losing its credits entirely.
 */
internal fun preferLatinNames(names: List<String>): List<String> {
    val cleaned = names.filter { it.isNotEmpty() }
    return cleaned.filterNot { name -> name.any { it.code >= CJK_BLOCK_START } }.ifEmpty { cleaned }
}

internal fun Element.toSeries(): NovelUpdatesSeries {
    val link = selectFirst(NuSelector.RESULT_TITLE)
    val cover = selectFirst(NuSelector.RESULT_COVER)?.attr("src").orEmpty()
    val body = selectFirst(NuSelector.RESULT_BODY)
    val hidden = body?.selectFirst(NuSelector.RESULT_HIDDEN_TEXT)?.text().orEmpty()
    val genres = select(NuSelector.RESULT_GENRES).text()

    return NovelUpdatesSeries(
        id = SID.find(selectFirst(NuSelector.RESULT_ID)?.attr("id").orEmpty())?.groupValues?.get(1),
        title = link?.text()?.trim().orEmpty(),
        seriesUrl = link?.attr("href").orEmpty(),
        coverUrl = if (cover.isBlank() || cover.startsWith("http")) {
            cover
        } else {
            "${NovelUpdatesApi.BASE_URL}$cover"
        },
        summary = stitchSummary(body?.text().orEmpty(), hidden),
        publishingStatus = when {
            genres.contains("Completed", ignoreCase = true) -> "Completed"
            genres.contains("Ongoing", ignoreCase = true) -> "Ongoing"
            else -> ""
        },
    )
}

/** The blurb is truncated with a hidden remainder, so the two halves are stitched back together. */
private fun stitchSummary(whole: String, hidden: String): String =
    (whole.replace(hidden, "") + " " + hidden)
        .replace("... more>>", "")
        .replace("<<less", "")
        .replace(WHITESPACE, " ")
        .trim()
