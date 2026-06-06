package yokai.presentation.novel.browse

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMFORTABLE_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COVER_ONLY_GRID
import yokai.domain.manga.models.MangaCover as MangaCoverModel
import yokai.i18n.MR
import yokai.novel.host.NovelItem
import yokai.presentation.manga.components.BadgeSegment
import yokai.presentation.manga.components.MangaComfortableGridItem
import yokai.presentation.manga.components.MangaCompactGridItem
import yokai.presentation.manga.components.MangaCoverRatio

/**
 * Browse-result parallel of [yokai.presentation.library.novels.NovelLibraryGridCell]. Browse
 * results are lightweight [NovelItem]s (name + cover url + path) with no persisted id or
 * unread/download counts, so this builds a [MangaCoverModel] straight from the item (`sourceId = 0L`,
 * like the library cell, since novel covers load by URL, not through manga-source Coil fetchers) and
 * renders through the same grid building blocks with the count badges off. [inLibrary] marks results
 * already favorited with an "In library" badge + dimmed cover, matching the manga catalogue
 * ([eu.kanade.tachiyomi.ui.source.browse.BrowseSourceGridHolder]).
 */
@Composable
fun NovelBrowseGridCell(
    item: NovelItem,
    inLibrary: Boolean,
    libraryLayout: Int,
    outlineOnCovers: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Cover aspect ratio. 2:3 (book) for the fixed grid; pass `null` on the staggered grid so each
     *  cover renders at its intrinsic ratio. */
    coverAspectRatio: Float? = MangaCoverRatio.BOOK,
) {
    val coverData = remember(item.path, inLibrary) {
        MangaCoverModel(
            mangaId = 0L,
            sourceId = 0L,
            url = item.cover ?: "",
            lastModified = 0L,
            inLibrary = inLibrary,
        )
    }
    val badgeSegments = if (inLibrary) {
        listOf(
            BadgeSegment.text(
                backgroundColor = MaterialTheme.colorScheme.secondary,
                text = stringResource(MR.strings.in_library),
                textColor = MaterialTheme.colorScheme.onSecondary,
            ),
        )
    } else {
        emptyList()
    }
    when (libraryLayout) {
        LAYOUT_COMFORTABLE_GRID -> MangaComfortableGridItem(
            coverData = coverData,
            title = item.name,
            modifier = modifier,
            badgeSegments = badgeSegments,
            isSelected = inLibrary,
            showOutline = outlineOnCovers,
            coverAspectRatio = coverAspectRatio,
            isLocal = false,
            onClick = onClick,
            onLongClick = onLongClick,
            onClickContinueReading = null,
            showLoadingIndicator = false,
        )
        LAYOUT_COVER_ONLY_GRID -> MangaCompactGridItem(
            coverData = coverData,
            title = item.name,
            modifier = modifier,
            badgeSegments = badgeSegments,
            isSelected = inLibrary,
            showOutline = outlineOnCovers,
            showTitle = false,
            coverAspectRatio = coverAspectRatio,
            isLocal = false,
            onClick = onClick,
            onLongClick = onLongClick,
            showLoadingIndicator = false,
        )
        else -> MangaCompactGridItem(
            coverData = coverData,
            title = item.name,
            modifier = modifier,
            badgeSegments = badgeSegments,
            isSelected = inLibrary,
            showOutline = outlineOnCovers,
            coverAspectRatio = coverAspectRatio,
            isLocal = false,
            onClick = onClick,
            onLongClick = onLongClick,
            onClickContinueReading = null,
            showLoadingIndicator = false,
        )
    }
}
