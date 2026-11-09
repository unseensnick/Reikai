package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.source.siteHost
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelSource
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource

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

    /** Either type's local source, which the folder badge already names on a local row. */
    data object Local : SourceBadge
}

fun mangaSourceBadge(source: Source): SourceBadge =
    if (source.id == LocalSource.ID) SourceBadge.Local else SourceBadge.Manga(source)

/** A manga source as its badge draws it: the installed source, or a stub once its extension is gone. */
suspend fun SourceManager.badgeSource(sourceId: Long): Source {
    val s = getOrStub(sourceId)
    return Source(s.id, s.lang, s.name, supportsLatest = false, isStub = s is StubSource)
}

/**
 * [source] is the registry's answer after its first load, null when it is not installed. An iconless
 * source borrows the icon of another installed source for the same site (by [siteHost], the identity
 * the app-icon fallback already borrows by), since two repos often package one site.
 */
fun novelSourceBadge(source: NovelSource?, iconsBySite: Map<String, String>): SourceBadge = when {
    source == null -> SourceBadge.Missing
    LocalNovelSource.isLocal(source.id) -> SourceBadge.Local
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

/**
 * The source icons the end badge group draws: one per grouped source, else the row's own. The local
 * source's is left out where the folder badge already shows, or a local row would draw two folders.
 */
fun LibraryItem.Badges.endSources(isMerged: Boolean): List<SourceBadge> =
    (if (isMerged) mergedSources else listOfNotNull(source)).filterNot { isLocal && it == SourceBadge.Local }
