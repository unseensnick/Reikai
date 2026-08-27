package reikai.presentation.browse.extension

import androidx.compose.runtime.Immutable
import reikai.domain.library.ContentType
import reikai.presentation.browse.compareBrowseLanguages

/** Identity for an installable source, whichever kind it is: a package name or a plugin id. */
sealed interface ExtensionKey {
    val contentType: ContentType

    data class Manga(val pkgName: String) : ExtensionKey {
        override val contentType: ContentType get() = ContentType.MANGA
    }

    data class Novel(val pluginId: String) : ExtensionKey {
        override val contentType: ContentType get() = ContentType.NOVELS
    }
}

/**
 * Where a row sits in the Extensions list. Updates and Installed are one section each across both
 * content types; what is available is split by language, as the manga list has always done.
 */
sealed interface ExtensionSection {
    data object Updates : ExtensionSection
    data object Installed : ExtensionSection
    data class Available(val lang: String) : ExtensionSection
}

/**
 * One installable source as the shared Extensions list sees it.
 *
 * [payload] is the provider's own object, unwrapped only by the leaf that renders its type. The two
 * search fields match differently: a term on containment, an id only as the whole query, which is
 * the rule the manga list already had. [needsAttention] lifts a row to the top of its section
 * because something is wrong with it, today only a manga extension gone obsolete.
 */
@Immutable
data class BrowseExtensionRow(
    val key: ExtensionKey,
    val name: String,
    val section: ExtensionSection,
    val needsAttention: Boolean,
    val searchTerms: List<String>,
    val searchIds: List<String>,
    val payload: Any,
)

/** A rendered Extensions list: section headings interleaved with the rows under them. */
sealed interface ExtensionsListItem {
    data class Header(val section: ExtensionSection) : ExtensionsListItem
    data class Row(val row: BrowseExtensionRow) : ExtensionsListItem
}

/**
 * Groups every row into the one sectioned list the Extensions tab draws: pending updates, then what
 * is installed, then what is available, a section per language. Mihon's order, for both content
 * types, so the chips cannot drift.
 */
fun sectionExtensions(rows: List<BrowseExtensionRow>): List<ExtensionsListItem> {
    val sections = rows
        .sortedWith(
            compareByDescending<BrowseExtensionRow> { it.needsAttention }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        .groupBy { it.section }
        .toSortedMap(SECTION_ORDER)
    return sections.flatMap { (section, rows) ->
        listOf(ExtensionsListItem.Header(section)) + rows.map { ExtensionsListItem.Row(it) }
    }
}

/**
 * Whether [row] survives the search box. Every comma-separated part is tried on its own and any hit
 * keeps the row, so "french, korean" widens rather than narrows.
 */
fun matchesExtensionQuery(row: BrowseExtensionRow, query: String?): Boolean {
    val subqueries = query.orEmpty().split(",").map { it.trim() }.filterNot { it.isBlank() }
    if (subqueries.isEmpty()) return true
    return subqueries.any { subquery ->
        row.searchTerms.any { it.contains(subquery, ignoreCase = true) } || subquery in row.searchIds
    }
}

private val SECTION_ORDER = Comparator<ExtensionSection> { a, b ->
    val byRank = a.rank().compareTo(b.rank())
    when {
        byRank != 0 -> byRank
        a is ExtensionSection.Available && b is ExtensionSection.Available ->
            compareBrowseLanguages(a.lang, b.lang)
        else -> 0
    }
}

private fun ExtensionSection.rank() = when (this) {
    ExtensionSection.Updates -> 0
    ExtensionSection.Installed -> 1
    is ExtensionSection.Available -> 2
}
