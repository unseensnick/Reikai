package reikai.presentation.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.domain.source.model.icon
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.data.coil.NovelCover
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.components.Badge
import tachiyomi.source.local.LocalSource

/**
 * Net-new Reikai cover badges, used by every library cell (single-list, panorama, and the pager's
 * grids). Kept in their own file so the badge logic stays out of Mihon's components.
 */

/**
 * Source/extension icon badge. Ported from Komikku's `SourceIconBadge`, re-typed onto Reikai's
 * domain [Source]. Resolves the icon via [Source.icon] (an ExtensionManager lookup); reading it in
 * the composable is the same Injekt-in-composable idiom Mihon/Komikku use for source icons.
 */
private const val MAX_MERGE_ICONS = 3

/**
 * Badge for a collapsed merge group (more than one grouped source). When [sources] is populated
 * (the "show source icons on merged covers" setting is on) it shows up to three distinct source
 * icons plus a "+N" overflow; otherwise it falls back to the numeric group count.
 */
@Composable
fun MergeBadge(relatedMangaIds: List<Long>, sources: List<Source>) {
    val count = relatedMangaIds.size
    if (count <= 1) return
    if (sources.isEmpty()) {
        Badge(text = count.toString())
        return
    }
    val distinct = sources.distinctBy { it.id }
    val shown = distinct.take(MAX_MERGE_ICONS)
    shown.forEach { SourceIconBadge(it) }
    val extra = distinct.size - shown.size
    if (extra > 0) Badge(text = "+$extra")
}

@Composable
fun SourceIconBadge(source: Source?) {
    if (source == null) return
    val icon = source.icon
    when {
        source.isStub && icon == null -> Badge(
            imageVector = Icons.Filled.Warning,
            color = MaterialTheme.colorScheme.errorContainer,
            iconColor = MaterialTheme.colorScheme.error,
        )
        icon != null -> Badge(
            imageBitmap = icon,
            modifier = Modifier
                .scale(1.3f)
                .height(18.dp),
        )
        source.id == LocalSource.ID -> Badge(
            imageVector = Icons.Outlined.Folder,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
        else -> Badge(
            imageVector = Icons.Outlined.LocalLibrary,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

/** Source-icon badge for a disguised novel: the source's icon is a CDN URL (novels carry no Mihon
 *  [Source] bitmap), so it's coil-loaded. Mirrors [SourceIconBadge]'s bitmap path exactly (same
 *  [Badge] geometry: a secondary-backed rectangle with the icon scaled to fill an 18dp square) so a
 *  novel cover's badge is visually identical to a manga's. Renders nothing when the URL is absent. */
@Composable
fun NovelSourceIconBadge(iconUrl: String?) {
    if (iconUrl.isNullOrEmpty()) return
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RectangleShape)
            .background(MaterialTheme.colorScheme.secondary),
    ) {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            modifier = Modifier
                .scale(1.3f)
                .height(18.dp),
        )
    }
}

/**
 * Cover data for a library row: a [NovelCover] (carries the source site as a Referer, loaded through
 * the novel cover pipeline) for a disguised novel (negative id), else the manga [MangaCover]. Returns
 * [Any] because the shared grid cells accept either model as coil data.
 */
fun libraryCoverModel(item: LibraryItem): Any {
    val manga = item.libraryManga.manga
    return if (manga.id < 0L) {
        NovelCover(
            url = manga.thumbnailUrl,
            site = item.badges.coverSite,
            isNovelFavorite = manga.favorite,
            lastModified = manga.coverLastModified,
        )
    } else {
        MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        )
    }
}

/** The end-of-cover badge: the merge badge for a grouped cover, otherwise the source icon (coil-loaded
 *  from a URL for a novel, the source bitmap for manga). Centralizes the merge/novel/manga branch so
 *  every library cell renders it identically. */
@Composable
fun LibraryCoverEndBadge(item: LibraryItem) {
    when {
        item.relatedMangaIds.size > 1 -> MergeBadge(item.relatedMangaIds, item.badges.mergedSources)
        item.libraryManga.manga.id < 0L -> NovelSourceIconBadge(item.badges.sourceIconUrl)
        else -> SourceIconBadge(item.badges.source)
    }
}
