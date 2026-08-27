package reikai.presentation.browse.source

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.ui.browse.source.SourcesViewModel
import reikai.domain.source.SourceKey
import reikai.presentation.browse.compareBrowseLanguages
import java.util.TreeMap

/**
 * One source as the shared Sources list sees it, whatever content type it came from.
 *
 * [source] is the provider's own object, carried opaquely so the shared layer never has to know
 * what a manga source or a plugin looks like; only the leaf that renders that type unwraps it.
 */
@Immutable
data class BrowseSourceRow(
    val key: SourceKey,
    val name: String,
    val lang: String,
    val isPinned: Boolean,
    val isUsedLast: Boolean,
    val source: Any,
)

/** A rendered Sources list: section headings interleaved with the rows under them. */
sealed interface SourcesListItem {
    data class Header(val key: String) : SourcesListItem
    data class Row(val row: BrowseSourceRow) : SourcesListItem
}

// The two section keys that are not languages are Mihon's own, so `LocaleHelper` renders their
// headings and there is one definition rather than a shared copy beside it.
private val LAST_USED_SECTION = SourcesViewModel.LAST_USED_KEY
private val PINNED_SECTION = SourcesViewModel.PINNED_KEY

/**
 * Groups every enabled source into the one sectioned list the Sources tab draws, whichever chip is
 * active: Last used, then Pinned, then one section per language, ordered by
 * [compareBrowseLanguages]. One order for both content types and for every Browse list.
 *
 * A row flagged [BrowseSourceRow.isUsedLast] is a *copy* the provider added, so the source appears
 * both under Last used and in its own section, which is what the manga list has always done.
 */
fun sectionSources(rows: List<BrowseSourceRow>): List<SourcesListItem> {
    val sections = TreeMap<String, MutableList<BrowseSourceRow>>(SECTION_ORDER)
    rows
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .groupByTo(sections) {
            when {
                it.isUsedLast -> LAST_USED_SECTION
                it.isPinned -> PINNED_SECTION
                else -> it.lang
            }
        }
    return sections.flatMap { (key, sources) ->
        listOf(SourcesListItem.Header(key)) + sources.map { SourcesListItem.Row(it) }
    }
}

private val SECTION_ORDER = Comparator<String> { a, b ->
    when {
        a == b -> 0
        a == LAST_USED_SECTION -> -1
        b == LAST_USED_SECTION -> 1
        a == PINNED_SECTION -> -1
        b == PINNED_SECTION -> 1
        else -> compareBrowseLanguages(a, b)
    }
}
