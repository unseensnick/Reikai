package reikai.presentation.details

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import eu.kanade.tachiyomi.data.download.model.Download
import reikai.domain.entry.EntryId

/**
 * The neutral details screen state both content types produce, so the shared details UI can render manga
 * and novels without branching on type. Each adapter (the novel one, and the manga one over the live model)
 * maps its own loaded state into [Loaded]. The per-type dialog is deliberately not here yet: dialog
 * rendering stays per-type until the shared dispatcher lands with the unified screen shell.
 */
sealed interface EntryDetailsScreenState {
    data object Loading : EntryDetailsScreenState

    data class Failed(val message: String) : EntryDetailsScreenState

    @Immutable
    data class Loaded(
        val entryId: EntryId,
        /** Header + action row + description data (shared with [entryInfoItems]). */
        val details: EntryDetailsUiState,
        val chapters: EntryChapterListUiState,
        /** Typed per-type slots; each adapter fills only what its type supports. */
        val capabilities: EntryCapabilities,
        /** The grouped sources for the switcher chips (empty or size 1 = not a merged group). */
        val mergeSources: List<EntryMergeSource>,
        /** The active source chip; null = the unified ("All") view. */
        val selectedSourceId: Long?,
        /** Drives the toolbar filter tint; the concrete filter/sort values stay per-type for now. */
        val hasActiveFilter: Boolean,
        val isRefreshing: Boolean,
        /** Selected chapter ids; a row is selected when its id is in here. Empty = not in selection mode. */
        val selection: Set<Long>,
        /** The chapter the resume FAB opens; null hides the FAB. */
        val resumeChapterId: Long?,
        /** At least one chapter is read, so the FAB reads "Resume" rather than "Start". */
        val hasStarted: Boolean,
        /** Downloads apply to this entry (false for a local/stub source); gates the download UI. */
        val chaptersDownloadable: Boolean,
        /** Show each chapter row as "Chapter N" rather than its title (manga display mode / novel hide-titles). */
        val showChapterNumberOnly: Boolean,
        /** Cover-derived header tint; null when off or not yet extracted. */
        val seedColor: Color?,
    ) : EntryDetailsScreenState {
        val selectionMode: Boolean get() = selection.isNotEmpty()
        val isMerged: Boolean get() = mergeSources.size > 1
    }
}

/** One grouped source in the merge switcher chips + manage-sources dialog. */
@Immutable
data class EntryMergeSource(val id: Long, val sourceName: String)

/**
 * The chapter region: the rendered rows (chapters interleaved with "N missing" separators) plus the
 * hidden-chapter view. Mirrors the novel's existing shape so the mapping is a rename, not a reshape.
 */
@Immutable
data class EntryChapterListUiState(
    val items: List<EntryChapterListItem>,
    /** Total missing chapters across the visible list; drives the header warning when > 0. */
    val missingChapterCount: Int,
    /** True while hidden chapters are temporarily shown (dimmed). */
    val showHidden: Boolean,
    /** Any chapter is hidden; gates the "Show hidden chapters" toolbar toggle. */
    val hasHiddenChapters: Boolean,
    /** Ids of displayed rows that are hidden (non-empty only when [showHidden]); drives dimming and
     *  whether the selection offers Hide vs Unhide. */
    val hiddenChapterIds: Set<Long>,
)

/**
 * One row in the neutral chapter list: a chapter or a "N missing chapters" separator. The neutral twin of
 * the manga `ChapterList.Item` / `ChapterList.MissingCount` and the novel [reikai.domain.novel.NovelChapterListEntry].
 */
sealed interface EntryChapterListItem {
    @Immutable
    data class Chapter(
        val id: Long,
        val name: String,
        /** Scanlator group; null for novels (no scanlator concept). */
        val scanlator: String?,
        val read: Boolean,
        val bookmark: Boolean,
        val dateUpload: Long,
        val chapterNumber: Double,
        val sourceOrder: Long,
        /** Pre-formatted resume hint ("42%" for novels, "Page 3" for manga); null when read or unstarted.
         *  Each adapter formats its own, so the neutral layer never sees page index vs scroll percent. */
        val readProgress: String?,
        /** Resolved download state (queue state, else disk membership); the adapter does the resolution. */
        val downloadState: Download.State,
        /** Live download percent for the spinner; 0 for novels (no per-chapter progress). */
        val downloadProgress: Int,
    ) : EntryChapterListItem {
        /** A real chapter number, so a cross-source dedup / gap check can trust it. Manga names this
         *  `isRecognizedNumber`; the novel side inlined `>= 0.0`. Same rule, one name. */
        val isRecognizedNumber: Boolean get() = chapterNumber >= 0.0
    }

    @Immutable
    data class Missing(val id: String, val count: Int) : EntryChapterListItem
}

/**
 * Per-type capability payloads for the details screen: typed slots, never nullable soup, each content type
 * filling only what it supports. The novel adapter fills [novelPageSelector]; the manga adapter fills the
 * three manga slots. E-Hentai account and scanlator filter are deliberately not slots here: the EH backup
 * lives inside the favourite toggle (already on the behaviour), and scanlator filtering is a filter-sheet
 * concern that stays per-type until the settings sheet unifies, so neither carries a display payload yet.
 * Adding a slot later is additive, not a breaking change to the shared spine.
 */
@Immutable
data class EntryCapabilities(
    val novelPageSelector: NovelPageSelectorCapability? = null,
    // Manga-only. Their payload types are defined with MangaEntryAdapter (they carry manga-engine types),
    // keeping this shared file free of manga-package imports. Null for novels.
    val mangaPagePreviews: MangaPagePreviewsCapability? = null,
    val mangaRelatedCarousel: MangaRelatedCarouselCapability? = null,
    // Non-null for every manga (novels have no namespaced tags/gallery), carrying the inputs for the
    // grouped tag chips + the gallery-info card; both render nothing for a normal (non-adult) manga.
    val mangaGallery: MangaGalleryCapability? = null,
)

/**
 * A paged novel source's page/volume selector. Not a downstream toggle: the page index is a live input to
 * the chapter flow, so the shared layer exposes the seam (the current index plus the page keys), and the
 * behaviour's `selectPage` re-runs the chapter pipeline. Null for manga and for single-page novels.
 */
@Immutable
data class NovelPageSelectorCapability(
    val pages: List<String>,
    val pageIndex: Int,
    val isPageLoading: Boolean,
) {
    /** More than one page/volume to choose between, so the selector is shown. */
    val isPaged: Boolean get() = pages.size > 1
}
