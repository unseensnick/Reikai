package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.source.siteHost
import reikai.novel.source.NovelSource
import tachiyomi.domain.source.model.Source

/**
 * A library row's source as a cover badge draws it, for both content types. A manga source is drawn
 * from its extension; a novel source's icon is an address. A source that is no longer installed is
 * [Missing], and an installed one with no icon is [Generic], the same for a manga and a novel.
 */
sealed interface SourceBadge {
    data class Manga(val source: Source) : SourceBadge
    data class Icon(val url: String) : SourceBadge
    data object Generic : SourceBadge
    data object Missing : SourceBadge
}

/**
 * [source] is the registry's answer after its first load, null when it is not installed. An iconless
 * source borrows the icon of another installed source for the same site (by [siteHost], the identity
 * the app-icon fallback already borrows by), since two repos often package one site.
 */
fun novelSourceBadge(source: NovelSource?, iconsBySite: Map<String, String>): SourceBadge = when {
    source == null -> SourceBadge.Missing
    else -> (source.iconUrl?.takeIf { it.isNotEmpty() } ?: siteHost(source.site)?.let(iconsBySite::get))
        ?.let(SourceBadge::Icon)
        ?: SourceBadge.Generic
}

/** The first icon each site's installed sources list, for [novelSourceBadge]. */
fun installedIconsBySite(sources: List<NovelSource>): Map<String, String> = buildMap {
    sources.forEach { source ->
        val icon = source.iconUrl?.takeIf { it.isNotEmpty() } ?: return@forEach
        siteHost(source.site)?.let { putIfAbsent(it, icon) }
    }
}

/** How many source icons the end badge group would draw: one per grouped source, else the row's own. */
fun LibraryItem.Badges.endSourceCount(isMerged: Boolean): Int = when {
    isMerged -> mergedSources.size
    source != null -> 1
    else -> 0
}
