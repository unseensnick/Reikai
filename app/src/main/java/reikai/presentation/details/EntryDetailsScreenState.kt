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
        /** Drives the toolbar filter tint; the concrete filter/sort values stay per-type for now. */
        val hasActiveFilter: Boolean,
        val isRefreshing: Boolean,
        /** Selected chapter ids; a row is selected when its id is in here. Empty = not in selection mode. */
        val selection: Set<Long>,
        /** The chapter the resume FAB opens; null hides the FAB. */
        val resumeChapterId: Long?,
        /** Cover-derived header tint; null when off or not yet extracted. */
        val seedColor: Color?,
    ) : EntryDetailsScreenState {
        val selectionMode: Boolean get() = selection.isNotEmpty()
    }
}

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
 * filling only what it supports. The novel adapter fills [novelPageSelector]. Manga's slots (page previews,
 * related carousel, gallery metadata, E-Hentai account, scanlator filter) are additive nullable slots that
 * land with the manga adapter; adding one then is not a breaking change to the shared spine.
 */
@Immutable
data class EntryCapabilities(
    val novelPageSelector: NovelPageSelectorCapability? = null,
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
