package yokai.presentation.details

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.model.SManga
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio

/**
 * Per-chapter download state for the row indicator. Presentation-level so the shared body never
 * sees the `Download.State` model; the manga screen maps into this and novels leave it [NONE].
 */
enum class DetailsDownloadState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, ERROR }

/**
 * Plain, type-agnostic view data for one chapter row. Both a manga `Chapter` and a `NovelChapter`
 * map into this, so [DetailsContent] never sees a domain type.
 */
data class DetailsChapterRow(
    val id: Long,
    val name: String,
    val read: Boolean,
    val bookmark: Boolean,
    val downloadState: DetailsDownloadState = DetailsDownloadState.NONE,
    /** 0..100, only meaningful while [downloadState] is [DetailsDownloadState.DOWNLOADING]. */
    val downloadProgress: Int = 0,
    val selected: Boolean = false,
)

/**
 * Plain view data for one related-manga carousel card. The screen maps a recommendation candidate
 * into this (resolving the cover model + provenance label) so [DetailsContent] holds no domain type.
 */
data class DetailsRelatedItem(
    /** Stable card key; the source manga url. The screen maps it back to the candidate on tap. */
    val key: String,
    val title: String,
    val coverData: Any?,
    /** Source display name, or the tracker name for tracker-origin suggestions. */
    val provenanceLabel: String,
)

/** One grouped source for the source-view chip row (merged titles only). */
data class DetailsSourceTab(val mangaId: Long, val label: String)

/**
 * Shared details body for manga and (later) novels. Pure renderer over already-resolved header
 * fields plus a list of [DetailsChapterRow]; holds no domain types so both surfaces feed it.
 *
 * The header mirrors upstream Yokai / Komikku: a blurred cover backdrop behind a side-by-side
 * cover + info block, an icon-over-label action row, and an expandable description with genre chips.
 * Capability callbacks are nullable so the novel surface can opt features out.
 */
// no ScreenModel: pure UI, no async state.
@Composable
fun DetailsContent(
    coverData: Any?,
    title: String,
    author: String?,
    artist: String?,
    status: Int,
    statusText: String?,
    sourceName: String,
    isStubSource: Boolean,
    description: String?,
    genres: List<String>,
    chapters: List<DetailsChapterRow>,
    onChapterClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Hoisted so the screen can drive the top-bar background alpha from the scroll position. */
    listState: LazyListState = rememberLazyListState(),
    /** Top inset (status bar + app bar height) so the header backdrop fills behind the transparent bar. */
    topInset: Dp = 0.dp,
    /** Bottom inset (nav bar) added to the list's bottom padding. */
    bottomInset: Dp = 0.dp,
    isFavorited: Boolean = false,
    /** When non-null, renders the favorite action button. Null = no favorite UI (future novel probe). */
    onFavoriteClick: (() -> Unit)? = null,
    /** Favorite long-press (edit categories). Null = no long-press. */
    onEditCategoryClick: (() -> Unit)? = null,
    /** Tapping the cover (zoom). Null = not clickable. */
    onCoverClick: (() -> Unit)? = null,
    /** True when at least one tracker is registered, so the Tracking button shows its active accent. */
    trackingActive: Boolean = false,
    /** When non-null, renders the Tracking action button. Null = no tracking UI (novels). */
    onTrackingClick: (() -> Unit)? = null,
    /** When non-null, renders the WebView action button. Null hides it (local sources / novels). */
    onWebViewClick: (() -> Unit)? = null,
    /** When non-null, renders the Share action button. Null hides it (local sources / novels). */
    onShareClick: (() -> Unit)? = null,
    /** Tapping the chapters header (open sort/filter). Null = not clickable. */
    onFilterClick: (() -> Unit)? = null,
    /** When non-null, each row shows a download indicator; tapping it calls back with the id. Null = no download UI (novels). */
    onDownloadClick: ((Long) -> Unit)? = null,
    /** True while at least one chapter is selected; rows toggle selection on tap instead of opening. */
    selectionActive: Boolean = false,
    /** When non-null, long-press (and, while [selectionActive], tap) toggles selection. Null = no multi-select (novels). */
    onToggleSelection: ((id: Long, selected: Boolean, fromLongPress: Boolean) -> Unit)? = null,
    /** Related-manga carousel cards; empty (or null [onRelatedClick]) hides the carousel. */
    relatedMangas: List<DetailsRelatedItem> = emptyList(),
    /** Full ranked-pool size, for the "See all (N)" label. */
    relatedMangasTotal: Int = 0,
    /** True while related mangas are still loading; a small spinner shows in the section header. */
    relatedMangasLoading: Boolean = false,
    /** Tapping a carousel card; receives the card key (manga url). Null hides the carousel. */
    onRelatedClick: ((String) -> Unit)? = null,
    /** Tapping "See all"; null hides the button. */
    onSeeAllClick: (() -> Unit)? = null,
    /** Grouped sources for the source-view chip row; empty (or null [onSourceViewChange]) hides it. */
    sourceTabs: List<DetailsSourceTab> = emptyList(),
    /** Currently selected source view: null = the unified stitched list, else a specific source. */
    selectedSourceView: Long? = null,
    /** Switch the source view; receives null for unified or a source's manga id. Null hides the chips. */
    onSourceViewChange: ((Long?) -> Unit)? = null,
    /** Long-press a source chip to remove it from the group; receives its manga id. Null = no long-press. */
    onSourceRemove: ((Long) -> Unit)? = null,
    /** The source being viewed (the anchor of this screen); its chip can't be long-press-removed. */
    currentSourceId: Long? = null,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // Leave room so the resume FAB doesn't cover the last chapter row.
        contentPadding = PaddingValues(bottom = 88.dp + bottomInset),
    ) {
        item(key = "header") {
            DetailsHeaderBox(
                coverData = coverData,
                title = title,
                author = author,
                artist = artist,
                status = status,
                statusText = statusText,
                sourceName = sourceName,
                isStubSource = isStubSource,
                onCoverClick = onCoverClick,
                topInset = topInset,
            )
        }
        if (onFavoriteClick != null) {
            item(key = "actions") {
                DetailsActionRow(
                    isFavorited = isFavorited,
                    onFavoriteClick = onFavoriteClick,
                    onEditCategoryClick = onEditCategoryClick,
                    trackingActive = trackingActive,
                    onTrackingClick = onTrackingClick,
                    onWebViewClick = onWebViewClick,
                    onShareClick = onShareClick,
                )
            }
        }
        if (sourceTabs.isNotEmpty() && onSourceViewChange != null) {
            item(key = "source_tabs") {
                SourceViewChips(
                    tabs = sourceTabs,
                    selected = selectedSourceView,
                    onSelect = onSourceViewChange,
                    onRemove = onSourceRemove,
                    currentSourceId = currentSourceId,
                )
            }
        }
        item(key = "description") {
            ExpandableDescription(description = description, genres = genres)
        }
        if ((relatedMangas.isNotEmpty() || relatedMangasLoading) && onRelatedClick != null) {
            item(key = "related") {
                RelatedMangaCarousel(
                    items = relatedMangas,
                    total = relatedMangasTotal,
                    loading = relatedMangasLoading,
                    onClick = onRelatedClick,
                    onSeeAllClick = onSeeAllClick,
                )
            }
        }
        item(key = "chapter_count") {
            ChapterHeader(count = chapters.size, onClick = onFilterClick)
        }
        items(items = chapters, key = { it.id }) { chapter ->
            DetailsChapterListRow(
                chapter = chapter,
                selectionActive = selectionActive,
                onClick = {
                    if (selectionActive && onToggleSelection != null) {
                        onToggleSelection(chapter.id, !chapter.selected, false)
                    } else {
                        onChapterClick(chapter.id)
                    }
                },
                onLongClick = onToggleSelection?.let { cb -> { cb(chapter.id, true, true) } },
                onDownloadClick = onDownloadClick?.let { cb -> { cb(chapter.id) } },
            )
        }
    }
}

@Composable
private fun DetailsHeaderBox(
    coverData: Any?,
    title: String,
    author: String?,
    artist: String?,
    status: Int,
    statusText: String?,
    sourceName: String,
    isStubSource: Boolean,
    onCoverClick: (() -> Unit)?,
    topInset: Dp,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // Blurred cover backdrop fading into the page background. Crop fills the width (without it
        // AsyncImage defaults to Fit, leaving a small letterboxed image). Blur only renders on
        // API 31+ (a no-op below, same as the legacy header's S+ gate); the surfaceTint backing
        // gives it body before the per-cover palette tint (Phase B) lands.
        val backgroundColor = MaterialTheme.colorScheme.background
        val tint = MaterialTheme.colorScheme.surfaceTint
        AsyncImage(
            model = coverData,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, backgroundColor),
                            startY = size.height / 2,
                        ),
                    )
                }
                .background(tint.copy(alpha = 0.4f))
                .blur(16.dp)
                .alpha(0.3f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = topInset + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Cover scales with screen width (legacy used a 0.25 width-percent capped at 200dp via
            // the sw600dp layouts), so it grows on tablets / unfolded foldables but stays ~100dp on
            // a phone.
            val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
            val coverWidth = (screenWidthDp * 0.25f).coerceIn(100.dp, 200.dp)
            MangaCover(
                data = coverData,
                ratio = MangaCoverRatio.BOOK,
                contentDescription = title,
                modifier = Modifier.width(coverWidth),
                onClick = onCoverClick,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                IconLabel(icon = Icons.Filled.Person, text = author?.takeIf { it.isNotBlank() } ?: "Unknown author")
                artist?.takeIf { it.isNotBlank() && it != author }?.let {
                    IconLabel(icon = Icons.Outlined.Brush, text = it)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = statusIcon(status),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                    )
                    Text(
                        text = buildString {
                            append(statusText?.takeIf { it.isNotBlank() } ?: "Unknown")
                            append("  •  ")
                            append(sourceName)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isStubSource) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp).size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp).padding(end = 4.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusIcon(status: Int): ImageVector = when (status) {
    SManga.ONGOING -> Icons.Outlined.Schedule
    SManga.COMPLETED -> Icons.Outlined.DoneAll
    SManga.LICENSED -> Icons.Outlined.MonetizationOn
    SManga.PUBLISHING_FINISHED -> Icons.Outlined.Done
    SManga.CANCELLED -> Icons.Outlined.Close
    SManga.ON_HIATUS -> Icons.Outlined.Pause
    else -> Icons.Outlined.Block
}

@Composable
private fun DetailsActionRow(
    isFavorited: Boolean,
    onFavoriteClick: () -> Unit,
    onEditCategoryClick: (() -> Unit)?,
    trackingActive: Boolean,
    onTrackingClick: (() -> Unit)?,
    onWebViewClick: (() -> Unit)?,
    onShareClick: (() -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        DetailsActionButton(
            label = if (isFavorited) "In library" else "Add to library",
            icon = if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            active = isFavorited,
            onClick = onFavoriteClick,
            onLongClick = onEditCategoryClick,
        )
        if (onTrackingClick != null) {
            DetailsActionButton(
                label = if (trackingActive) "Tracked" else "Tracking",
                icon = if (trackingActive) Icons.Outlined.Done else Icons.Outlined.Sync,
                active = trackingActive,
                onClick = onTrackingClick,
            )
        }
        if (onWebViewClick != null) {
            DetailsActionButton(
                label = "WebView",
                icon = Icons.Outlined.Public,
                active = false,
                onClick = onWebViewClick,
            )
        }
        if (onShareClick != null) {
            DetailsActionButton(
                label = "Share",
                icon = Icons.Outlined.IosShare,
                active = false,
                onClick = onShareClick,
            )
        }
    }
}

@Composable
private fun RowScope.DetailsActionButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SourceViewChips(
    tabs: List<DetailsSourceTab>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    onRemove: ((Long) -> Unit)?,
    currentSourceId: Long?,
) {
    // "Unified" plus one chip per grouped source. Unified shows the stitched list; a source chip
    // shows (and reads from) just that source. Long-pressing a source chip removes it from the
    // group. Scrolls horizontally when there are many sources.
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "unified") {
            SourceChip(label = "Unified", selected = selected == null, onClick = { onSelect(null) }, onLongClick = null)
        }
        items(items = tabs, key = { it.mangaId }) { tab ->
            SourceChip(
                label = tab.label,
                selected = selected == tab.mangaId,
                onClick = { onSelect(tab.mangaId) },
                // The source being viewed is the screen's anchor, so it can't be removed from the group.
                onLongClick = if (tab.mangaId == currentSourceId) null else onRemove?.let { cb -> { cb(tab.mangaId) } },
            )
        }
    }
}

@Composable
private fun SourceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Done,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp).padding(end = 4.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ExpandableDescription(description: String?, genres: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val text = description?.takeIf { it.isNotBlank() } ?: "No description"
    val backgroundColor = MaterialTheme.colorScheme.background
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .animateContentSize(),
    ) {
        // Description text and the expand caret are a single tap target with no ripple: tapping
        // anywhere on the text or the caret toggles expansion. Genre chips sit outside it so they
        // keep their own taps.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // Soft fade over the clamped last line, hinting there's more to read.
                if (!expanded) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0.6f to Color.Transparent,
                                    1f to backgroundColor,
                                ),
                            ),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (genres.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    genres.forEach { GenreChip(it) }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items = genres) { GenreChip(it) }
                }
            }
        }
    }
}

@Composable
private fun GenreChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ChapterHeader(count: Int, onClick: (() -> Unit)?) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$count chapter${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            // Only the icon opens filter/sort, not the whole row.
            if (onClick != null) {
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = "Filter and sort",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.padding(end = 12.dp))
    }
}

@Composable
private fun DetailsChapterListRow(
    chapter: DetailsChapterRow,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onDownloadClick: (() -> Unit)?,
) {
    val contentColor = if (chapter.read) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val rowBackground = if (chapter.selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Fixed height so rows don't shrink in selection mode (a bare 24dp check icon) vs normal
            // mode (the ~48dp download IconButton); content-driven height made the two differ.
            .height(56.dp)
            .background(rowBackground)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (chapter.bookmark) {
            Icon(
                imageVector = Icons.Filled.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(16.dp),
            )
        }
        Text(
            text = chapter.name,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        when {
            selectionActive -> Icon(
                imageVector = if (chapter.selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (chapter.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            onDownloadClick != null -> ChapterDownloadIndicator(
                state = chapter.downloadState,
                progress = chapter.downloadProgress,
                onClick = onDownloadClick,
            )
        }
    }
}

@Composable
private fun ChapterDownloadIndicator(
    state: DetailsDownloadState,
    progress: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        when (state) {
            DetailsDownloadState.NONE -> Icon(
                imageVector = Icons.Outlined.ArrowCircleDown,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            DetailsDownloadState.QUEUED -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            DetailsDownloadState.DOWNLOADING -> CircularProgressIndicator(
                progress = { progress / 100f },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            DetailsDownloadState.DOWNLOADED -> Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Delete download",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            DetailsDownloadState.ERROR -> Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "Retry download",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun RelatedMangaCarousel(
    items: List<DetailsRelatedItem>,
    total: Int,
    loading: Boolean,
    onClick: (String) -> Unit,
    onSeeAllClick: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Related",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (onSeeAllClick != null && items.isNotEmpty()) {
                Text(
                    text = "See all (${total.coerceAtLeast(items.size)})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = onSeeAllClick)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        if (items.isEmpty()) {
            // Loading skeleton: the source's related endpoint can take a while on a cold fetch, so
            // show placeholders immediately instead of a blank gap until results arrive.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(4) { RelatedMangaSkeletonCard() }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = items, key = { it.key }) { item ->
                    RelatedMangaCard(item = item, onClick = { onClick(item.key) })
                }
            }
        }
    }
}

@Composable
private fun RelatedMangaSkeletonCard() {
    val placeholder = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    Column(modifier = Modifier.width(110.dp)) {
        Box(
            modifier = Modifier
                .width(110.dp)
                .height(165.dp)
                .background(placeholder, RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(placeholder, RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun RelatedMangaCard(item: DetailsRelatedItem, onClick: () -> Unit) {
    Column(modifier = Modifier.width(110.dp)) {
        MangaCover(
            data = item.coverData,
            ratio = MangaCoverRatio.BOOK,
            contentDescription = item.title,
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                text = item.provenanceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
