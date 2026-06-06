package yokai.presentation.details

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.BookmarkRemove
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
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.icerock.moko.resources.compose.pluralStringResource
import eu.kanade.tachiyomi.source.model.SManga
import yokai.i18n.MR
import yokai.presentation.core.components.VerticalFastScroller
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio

/**
 * Per-chapter download state for the row indicator. Presentation-level so the shared body never
 * sees the `Download.State` model; the manga screen maps into this and novels leave it [NONE].
 */
enum class DetailsDownloadState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, ERROR }

/** Action chosen from a chapter's download indicator: a plain tap (START/DELETE) or a menu pick. */
enum class ChapterDownloadAction { START, START_NOW, CANCEL, DELETE }

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
    /** Rendered dimmed; used by the novel "Show hidden chapters" view to mark hidden rows. */
    val dimmed: Boolean = false,
    /** Secondary line: relative upload date. Null hides it (and on the novel surface, which omits all three). */
    val date: String? = null,
    /** Secondary line: "Page X of Y" partial-read progress; only set for partially-read chapters. */
    val readProgress: String? = null,
    /** Secondary line: scanlator/group name, when the chapter has one. */
    val scanlator: String? = null,
    /** Whole chapter numbers missing before this row (number-sort only); renders a divider above it. */
    val missingCount: Int = 0,
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
    /** Per-cover accent tint for the header backdrop; null keeps the flat theme color (novels). */
    accentColor: Color? = null,
    isFavorited: Boolean = false,
    /** When non-null, renders the favorite action button. Null = no favorite UI (future novel probe). */
    onFavoriteClick: (() -> Unit)? = null,
    /** Favorite long-press menu: edit categories. Null hides the item. */
    onEditCategoryClick: (() -> Unit)? = null,
    /** Favorite long-press menu: remove from library. Null hides the item. */
    onRemoveFromLibrary: (() -> Unit)? = null,
    /** Favorite long-press menu: remove all grouped sources (merged titles). Null hides the item. */
    onRemoveAllSources: (() -> Unit)? = null,
    /** Tapping the cover (zoom). Null = not clickable. */
    onCoverClick: (() -> Unit)? = null,
    /** Tap a tag / title / author / artist to search for it. Null disables (novels). */
    onSearch: ((String) -> Unit)? = null,
    /** Long-press a tag / title / author / artist to copy it. Null disables. */
    onCopy: ((String) -> Unit)? = null,
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
    /** True when any chapter filter is active (read/downloaded/bookmarked/scanlator); tints the filter icon. */
    filtersActive: Boolean = false,
    /** When non-null, each row shows a download indicator; it calls back with the chapter id + chosen action. Null = no download UI (novels). */
    onDownloadClick: ((Long, ChapterDownloadAction) -> Unit)? = null,
    /** When true the indicator offers a dropdown (Start now / Cancel / Delete) for in-progress/downloaded chapters; false keeps a plain single-tap toggle (novels). */
    downloadMenuEnabled: Boolean = false,
    /** Enables row swipe gestures (when both swipe callbacks are set and not selecting). */
    chapterSwipeEnabled: Boolean = false,
    /** Swipe-left a row to toggle its read state. Null disables that swipe (novels). */
    onSwipeToRead: ((Long) -> Unit)? = null,
    /** Swipe-right a row to toggle its bookmark. Null disables that swipe (novels). */
    onSwipeToBookmark: ((Long) -> Unit)? = null,
    /** True while at least one chapter is selected; rows toggle selection on tap instead of opening. */
    selectionActive: Boolean = false,
    /** When non-null, long-press (and, while [selectionActive], tap) toggles selection. Null = no multi-select (novels). */
    onToggleSelection: ((id: Long, selected: Boolean, fromLongPress: Boolean) -> Unit)? = null,
    /** Two-finger range select: pressing two chapter rows at once selects everything between them
     *  (receives the two chapter ids). Null = disabled. */
    onRangeSelect: ((firstId: Long, secondId: Long) -> Unit)? = null,
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
    val currentRangeSelect = rememberUpdatedState(onRangeSelect)
    Box(modifier = modifier.fillMaxSize()) {
        VerticalFastScroller(
            listState = listState,
            thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            topContentPadding = topInset,
            bottomContentPadding = 88.dp + bottomInset,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .twoFingerRangeSelect(listState) { a, b -> currentRangeSelect.value?.invoke(a, b) },
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
                        accentColor = accentColor,
                        onCoverClick = onCoverClick,
                        onSearch = onSearch,
                        onCopy = onCopy,
                        topInset = topInset,
                        // Parallax: the blurred backdrop drifts at a fraction of the list scroll while
                        // the header is the first item, so it sinks slower than the chapters.
                        scrollOffset = {
                            if (listState.firstVisibleItemIndex == 0) {
                                listState.firstVisibleItemScrollOffset.toFloat()
                            } else {
                                0f
                            }
                        },
                    )
                }
                if (onFavoriteClick != null) {
                    item(key = "actions") {
                        DetailsActionRow(
                            isFavorited = isFavorited,
                            onFavoriteClick = onFavoriteClick,
                            onEditCategoryClick = onEditCategoryClick,
                            onRemoveFromLibrary = onRemoveFromLibrary,
                            onRemoveAllSources = onRemoveAllSources,
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
                    ExpandableDescription(description = description, genres = genres, onSearch = onSearch, onCopy = onCopy)
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
                    ChapterHeader(count = chapters.size, onClick = onFilterClick, filtersActive = filtersActive)
                }
                items(items = chapters, key = { it.id }) { chapter ->
                    if (chapter.missingCount > 0) {
                        MissingChapterCountListItem(count = chapter.missingCount)
                    }
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
                        onDownloadClick = onDownloadClick?.let { cb -> { action -> cb(chapter.id, action) } },
                        downloadMenuEnabled = downloadMenuEnabled,
                        swipeEnabled = chapterSwipeEnabled,
                        onSwipeToRead = onSwipeToRead?.let { cb -> { cb(chapter.id) } },
                        onSwipeToBookmark = onSwipeToBookmark?.let { cb -> { cb(chapter.id) } },
                    )
                }
            }
        }
    }
}

// Fraction of the list scroll the blurred backdrop lags behind by (subtle depth, not a full parallax).
private const val BackdropParallaxFactor = 0.2f

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
    accentColor: Color?,
    onCoverClick: (() -> Unit)?,
    onSearch: ((String) -> Unit)?,
    onCopy: ((String) -> Unit)?,
    topInset: Dp,
    scrollOffset: () -> Float,
) {
    // clipToBounds keeps the parallax-translated backdrop from bleeding into the row below.
    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
        // Blurred cover backdrop fading into the page background. Crop fills the width (without it
        // AsyncImage defaults to Fit, leaving a small letterboxed image). Blur only renders on
        // API 31+ (a no-op below, same as the legacy header's S+ gate). Tinted by the per-cover
        // accent when available, else a flat surfaceTint backing.
        val backgroundColor = MaterialTheme.colorScheme.background
        val tint = accentColor ?: MaterialTheme.colorScheme.surfaceTint
        AsyncImage(
            model = coverData,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { translationY = scrollOffset() * BackdropParallaxFactor }
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
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.searchable(title, onSearch, onCopy),
                )
                val authorText = author?.takeIf { it.isNotBlank() }
                IconLabel(
                    icon = Icons.Filled.Person,
                    text = authorText ?: "Unknown author",
                    modifier = if (authorText != null) Modifier.searchable(authorText, onSearch, onCopy) else Modifier,
                )
                artist?.takeIf { it.isNotBlank() && it != author }?.let { artistText ->
                    IconLabel(
                        icon = Icons.Outlined.Brush,
                        text = artistText,
                        modifier = Modifier.searchable(artistText, onSearch, onCopy),
                    )
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
private fun IconLabel(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
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
    onRemoveFromLibrary: (() -> Unit)?,
    onRemoveAllSources: (() -> Unit)?,
    trackingActive: Boolean,
    onTrackingClick: (() -> Unit)?,
    onWebViewClick: (() -> Unit)?,
    onShareClick: (() -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        // Favorite + its long-press menu live in a weighted Box so the menu anchors to the button.
        Box(modifier = Modifier.weight(1f)) {
            var favMenuOpen by remember { mutableStateOf(false) }
            val hasMenu = onEditCategoryClick != null || onRemoveFromLibrary != null || onRemoveAllSources != null
            DetailsActionButton(
                modifier = Modifier.fillMaxWidth(),
                label = if (isFavorited) "In library" else "Add to library",
                icon = if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                active = isFavorited,
                onClick = onFavoriteClick,
                onLongClick = if (hasMenu) ({ favMenuOpen = true }) else null,
            )
            DropdownMenu(expanded = favMenuOpen, onDismissRequest = { favMenuOpen = false }) {
                if (onEditCategoryClick != null) {
                    DropdownMenuItem(text = { Text("Edit categories") }, onClick = { favMenuOpen = false; onEditCategoryClick() })
                }
                if (onRemoveFromLibrary != null) {
                    DropdownMenuItem(text = { Text("Remove from library") }, onClick = { favMenuOpen = false; onRemoveFromLibrary() })
                }
                if (onRemoveAllSources != null) {
                    DropdownMenuItem(text = { Text("Remove all sources from library") }, onClick = { favMenuOpen = false; onRemoveAllSources() })
                }
            }
        }
        if (onTrackingClick != null) {
            DetailsActionButton(
                modifier = Modifier.weight(1f),
                label = if (trackingActive) "Tracked" else "Tracking",
                icon = if (trackingActive) Icons.Outlined.Done else Icons.Outlined.Sync,
                active = trackingActive,
                onClick = onTrackingClick,
            )
        }
        if (onWebViewClick != null) {
            DetailsActionButton(
                modifier = Modifier.weight(1f),
                label = "WebView",
                icon = Icons.Outlined.Public,
                active = false,
                onClick = onWebViewClick,
            )
        }
        if (onShareClick != null) {
            DetailsActionButton(
                modifier = Modifier.weight(1f),
                label = "Share",
                icon = Icons.Outlined.IosShare,
                active = false,
                onClick = onShareClick,
            )
        }
    }
}

@Composable
private fun DetailsActionButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    }
    Column(
        modifier = modifier
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
private fun ExpandableDescription(
    description: String?,
    genres: List<String>,
    onSearch: ((String) -> Unit)?,
    onCopy: ((String) -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val text = description?.takeIf { it.isNotBlank() } ?: "No description"
    val backgroundColor = MaterialTheme.colorScheme.background
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .animateContentSize(),
    ) {
        // Expanded: full markdown (tappable links) in a SelectionContainer so the text can be copied.
        // Collapsed: a plain 3-line preview with a fade. Only the caret toggles, leaving the body's
        // taps free for link-following / text selection.
        Column(modifier = Modifier.fillMaxWidth()) {
            if (expanded) {
                SelectionContainer {
                    MarkdownRender(content = text)
                }
            } else {
                // Same markdown, height-clamped (MarkdownRender has no maxLines) with a fade over the cut.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 72.dp)
                        .clipToBounds(),
                ) {
                    MarkdownRender(content = text)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = !expanded }
                    .padding(vertical = 4.dp),
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
                    genres.forEach { GenreChip(it, onSearch, onCopy) }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items = genres) { GenreChip(it, onSearch, onCopy) }
                }
            }
        }
        // Trailing gap so the tags don't butt against whatever follows (the chapter header when
        // there's no related carousel, as on the novel screen and single-source manga).
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GenreChip(text: String, onSearch: ((String) -> Unit)?, onCopy: ((String) -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(vertical = 4.dp).searchable(text, onSearch, onCopy),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Tap to search for [value], long-press to copy it. A no-op when both callbacks are null (novels). */
@Composable
private fun Modifier.searchable(
    value: String,
    onSearch: ((String) -> Unit)?,
    onCopy: ((String) -> Unit)?,
): Modifier = if (onSearch == null && onCopy == null) {
    this
} else {
    this.combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = { onSearch?.invoke(value) },
        onLongClick = onCopy?.let { copy -> { copy(value) } },
    )
}

@Composable
private fun ChapterHeader(count: Int, onClick: (() -> Unit)?, filtersActive: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$count chapter${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            // Only the icon opens filter/sort, not the whole row. Accented while a filter is active,
            // muted otherwise, so the header signals when chapters are being hidden.
            if (onClick != null) {
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = "Filter and sort",
                        tint = if (filtersActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.padding(end = 12.dp))
    }
}

/** Divider-flanked "Missing N chapters" marker shown above a row when chapters are skipped (number-sort). */
@Composable
private fun MissingChapterCountListItem(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = pluralStringResource(MR.plurals.missing_chapters, quantity = count, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DetailsChapterListRow(
    chapter: DetailsChapterRow,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onDownloadClick: ((ChapterDownloadAction) -> Unit)?,
    downloadMenuEnabled: Boolean,
    swipeEnabled: Boolean,
    onSwipeToRead: (() -> Unit)?,
    onSwipeToBookmark: (() -> Unit)?,
) {
    val canSwipe = swipeEnabled && !selectionActive && onSwipeToRead != null && onSwipeToBookmark != null
    val contentColor = if (chapter.read) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val rowBackground = when {
        chapter.selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        // Opaque so the swipe-reveal background only shows where the row is dragged.
        canSwipe -> MaterialTheme.colorScheme.surface
        else -> Color.Transparent
    }
    val rowContent = @Composable {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Min height keeps short rows tap-friendly; a second metadata line grows it from there.
            .heightIn(min = 56.dp)
            .then(if (chapter.dimmed) Modifier.alpha(0.4f) else Modifier)
            .background(rowBackground)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (chapter.bookmark) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = chapter.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // Secondary line: date • read-progress • scanlator, joined like the legacy row.
            val subtitle = listOfNotNull(chapter.date, chapter.readProgress, chapter.scanlator)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle.joinToString("  •  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (chapter.read) 0.4f else 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
                enableMenu = downloadMenuEnabled,
                onAction = onDownloadClick,
            )
        }
    }
    }
    if (canSwipe) {
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> onSwipeToBookmark?.invoke()
                    SwipeToDismissBoxValue.EndToStart -> onSwipeToRead?.invoke()
                    SwipeToDismissBoxValue.Settled -> {}
                }
                // Never actually dismiss the row; fire the action and snap back.
                false
            },
        )
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = { ChapterSwipeBackground(dismissState, chapter) },
            content = { rowContent() },
        )
    } else {
        rowContent()
    }
}

/** Swipe-reveal behind a chapter row: bookmark when swiping right (start->end), toggle read when swiping left. */
@Composable
private fun ChapterSwipeBackground(state: SwipeToDismissBoxState, chapter: DetailsChapterRow) {
    val direction = state.dismissDirection
    val icon = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> if (chapter.bookmark) Icons.Outlined.BookmarkRemove else Icons.Filled.Bookmark
        SwipeToDismissBoxValue.EndToStart -> if (chapter.read) Icons.Outlined.RemoveDone else Icons.Outlined.Done
        SwipeToDismissBoxValue.Settled -> null
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 20.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ChapterDownloadIndicator(
    state: DetailsDownloadState,
    progress: Int,
    enableMenu: Boolean,
    onAction: (ChapterDownloadAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    IconButton(
        onClick = {
            when {
                state == DetailsDownloadState.NONE || state == DetailsDownloadState.ERROR -> onAction(ChapterDownloadAction.START)
                // Without the menu (novels) a tap just removes/cancels, matching the old single-tap toggle.
                !enableMenu -> onAction(ChapterDownloadAction.DELETE)
                else -> menuOpen = true
            }
        },
        modifier = Modifier.size(32.dp),
    ) {
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
        if (enableMenu) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                when (state) {
                    DetailsDownloadState.QUEUED, DetailsDownloadState.DOWNLOADING -> {
                        DropdownMenuItem(
                            text = { Text("Start downloading now") },
                            onClick = { menuOpen = false; onAction(ChapterDownloadAction.START_NOW) },
                        )
                        DropdownMenuItem(
                            text = { Text("Cancel") },
                            onClick = { menuOpen = false; onAction(ChapterDownloadAction.CANCEL) },
                        )
                    }
                    DetailsDownloadState.DOWNLOADED -> DropdownMenuItem(
                        text = { Text("Delete download") },
                        onClick = { menuOpen = false; onAction(ChapterDownloadAction.DELETE) },
                    )
                    else -> {}
                }
            }
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

/**
 * Detect two fingers pressed on two different chapter rows at once and fire [onRange] with their
 * chapter ids (the chapter list keys its rows by id). Observes the Initial pass and consumes so the
 * two-finger gesture wins over the list scroll; single-finger input is untouched (tap / long-press /
 * scroll all still work).
 */
private fun Modifier.twoFingerRangeSelect(
    listState: LazyListState,
    onRange: (Long, Long) -> Unit,
): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val idA = listState.chapterIdAtY(pressed[0].position.y)
                val idB = listState.chapterIdAtY(pressed[1].position.y)
                if (idA != null && idB != null && idA != idB) {
                    onRange(idA, idB)
                    pressed.forEach { it.consume() }
                    // Swallow the rest of the gesture so it neither scrolls nor refires.
                    while (currentEvent.changes.any { it.pressed }) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
        }
    }
}

/** Chapter id under the given y (in the list's coordinate space), or null if y is over a non-chapter
 *  row (header/description/etc.). Chapter rows are keyed by their Long id; other rows use String keys. */
private fun LazyListState.chapterIdAtY(y: Float): Long? {
    val yi = y.toInt()
    val item = layoutInfo.visibleItemsInfo.firstOrNull { yi >= it.offset && yi < it.offset + it.size } ?: return null
    return item.key as? Long
}
