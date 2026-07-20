package reikai.presentation.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.domain.source.model.icon
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.assets.EhAssets
import exh.assets.ehassets.EhLogo
import exh.source.NHENTAI_NET_SOURCE_ID
import exh.source.PURURIN_SOURCE_ID
import exh.source.eHentaiSourceIds
import reikai.data.coil.NovelCover
import reikai.domain.entry.EntryId
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.components.Badge
import tachiyomi.source.local.LocalSource

/*
 * Net-new Reikai cover badges, used by every library cell (single-list, panorama, and the pager's
 * grids). Kept in their own file so the badge logic stays out of Mihon's components.
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

/**
 * Merge badge for a collapsed NOVEL group, the coil-loaded twin of [MergeBadge]. When [iconUrls] is
 * populated (the "show source icons on merged covers" novel setting is on) it shows up to three source
 * icons plus a "+N" overflow; otherwise it falls back to the numeric group count.
 */
@Composable
fun NovelMergeBadge(relatedMangaIds: List<Long>, iconUrls: List<String>) {
    val count = relatedMangaIds.size
    if (count <= 1) return
    if (iconUrls.isEmpty()) {
        Badge(text = count.toString())
        return
    }
    val shown = iconUrls.take(MAX_MERGE_ICONS)
    shown.forEach { NovelSourceIconBadge(it) }
    val extra = iconUrls.size - shown.size
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
        // built-in E-Hentai / ExHentai ship no extension icon, so draw the EH mark on a white
        //     tile (same treatment as the browse SourceIcon) instead of the generic library glyph.
        source.id in eHentaiSourceIds -> EhSourceIconBadge()
        // built-in Pururin / nhentai.net likewise ship no extension icon; give each its logo so the
        //     library badge matches the browse source icon instead of the generic library glyph.
        source.id == PURURIN_SOURCE_ID -> PururinSourceIconBadge()
        source.id == NHENTAI_NET_SOURCE_ID -> NHentaiNetSourceIconBadge()
        else -> Badge(
            imageVector = Icons.Outlined.LocalLibrary,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

/** Source-icon badge for the built-in E-Hentai / ExHentai sources. The brand mark (its own dark red)
 *  is drawn untinted on a white tile so it reads on both themes, matching the browse [SourceIcon];
 *  scaled to the same footprint as the bitmap source badge. */
@Composable
private fun EhSourceIconBadge() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RectangleShape)
            .background(Color.White)
            .height(18.dp)
            .aspectRatio(1f),
    ) {
        Image(
            imageVector = EhAssets.EhLogo,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.8f),
        )
    }
}

/** Source-icon badge for the built-in Pururin source. Its logo sits on the same white tile as the
 *  E-Hentai mark so the two built-in adult sources read consistently. */
@Composable
private fun PururinSourceIconBadge() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RectangleShape)
            .background(Color.White)
            .height(18.dp)
            .aspectRatio(1f),
    ) {
        Image(
            painter = painterResource(R.drawable.pururin_logo),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.8f),
        )
    }
}

/** Source-icon badge for the built-in nhentai.net source. Its logo already carries a dark backdrop,
 *  so it's drawn edge-to-edge with no tile (matching the browse SourceIcon). */
@Composable
private fun NHentaiNetSourceIconBadge() {
    Image(
        painter = painterResource(R.drawable.nhentai_logo),
        contentDescription = null,
        modifier = Modifier
            .clip(RectangleShape)
            .height(18.dp)
            .aspectRatio(1f),
    )
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
 * the novel cover pipeline) for a novel, else the manga [MangaCover]. Returns [Any] because the shared
 * grid cells accept either model as coil data.
 */
fun libraryCoverModel(item: LibraryItem): Any {
    val manga = item.libraryManga.manga
    val entryId = item.entryId
    return if (entryId is EntryId.Novel) {
        NovelCover(
            url = manga.thumbnailUrl,
            site = item.badges.coverSite,
            isNovelFavorite = manga.favorite,
            lastModified = manga.coverLastModified,
            // The neutral identity carries the real novel id; the leaf row still disguises it as a
            // negative-id Manga (2b retires that), so read the id here, not from `manga.id`.
            novelId = entryId.rawId,
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
    val isNovel = item.entryId is EntryId.Novel
    when {
        // A merged novel renders coil-loaded source icons; a merged manga the bitmap ones.
        item.relatedMangaIds.size > 1 && isNovel ->
            NovelMergeBadge(item.relatedMangaIds, item.badges.mergedSourceIconUrls)
        item.relatedMangaIds.size > 1 -> MergeBadge(item.relatedMangaIds, item.badges.mergedSources)
        isNovel -> NovelSourceIconBadge(item.badges.sourceIconUrl)
        else -> SourceIconBadge(item.badges.source)
    }
}
