package reikai.presentation.details

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import eu.kanade.tachiyomi.data.download.model.Download
import reikai.domain.download.offersDownload
import reikai.domain.entry.EntryId
import reikai.domain.merge.DownloadTargets
import reikai.domain.reader.ChapterProgress
import reikai.presentation.components.UndatedChapterDate
import reikai.presentation.selection.ChapterMarks

/**
 * The neutral details screen state both content types produce, so the shared details UI can render manga
 * and novels without branching on type. Each adapter (the novel one, and the manga one over the live model)
 * maps its own loaded state into [Loaded]. The dialog is not here: each screen maps its own dialog onto
 * the shared EntryDetailsDialog, and renders the dialogs only one type has itself.
 */
sealed interface EntryDetailsScreenState {
    data object Loading : EntryDetailsScreenState

    data class Failed(val message: String) : EntryDetailsScreenState

    @Immutable
    data class Loaded(
        val entryId: EntryId,
        /** The member the page shows: the selected chip's, else the anchor's own [entryId]. */
        val viewedEntryId: Long,
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
        /** A viewed member holds files on disk, so Open folder and Clear downloads have something to act
         *  on. Answered by each adapter through [downloadFolderOwner], never from the rows. */
        val hasViewedDownloads: Boolean,
        /** Show each chapter row as "Chapter N" rather than its title (manga display mode / novel hide-titles). */
        val showChapterNumberOnly: Boolean,
        /** Cover-derived header tint; null when off or not yet extracted. */
        val seedColor: Color?,
        /** The viewed member's page, which each model resolves off the render path; null hides the web actions. */
        val webPage: EntryWebPage?,
        /** The copy each row's download fetches, which the All view moves off a member whose source is gone. */
        val downloadTargets: DownloadTargets,
    ) : EntryDetailsScreenState {
        val selectionMode: Boolean get() = selection.isNotEmpty()
        val isMerged: Boolean get() = mergeSources.size > 1

        /** Downloads go through the viewed member's own source, so only an installed one gates them on. */
        val chaptersDownloadable: Boolean get() = details.header.sourceState == EntrySourceState.Installed

        /**
         * Whether a chapter row in [downloadState] draws its download indicator and takes a download swipe.
         * A missing source can start nothing, and neither can a merged row no installed source holds, so a
         * row with nothing on disk or queued offers neither, where Mihon draws an indicator that does
         * nothing. A local row keeps Mihon's disabled one.
         */
        fun rowOffersDownload(chapterId: Long, downloadState: Download.State): Boolean =
            downloadTargets.offersDownload(chapterId, downloadState) {
                details.header.sourceState != EntrySourceState.Missing
            }

        /** A custom cover lands on the entry the library renders, so only the anchor's may be edited. */
        val isCoverAnchored: Boolean get() = viewedEntryId == entryId.rawId
    }
}

/** What the viewed member's source is: the header warns on [Missing], and only [Installed] downloads. */
enum class EntrySourceState {
    Installed,

    /** Manga's local source, whose files are the series itself. Novels have no local source. */
    Local,

    /** Uninstalled: a manga stub source, or a novel whose plugin or extension is gone. */
    Missing,
}

/** One grouped source in the merge switcher chips + manage-sources dialog. [id] is the member
 *  entry's id, not a source id. */
@Immutable
data class EntryMergeSource(val id: Long, val sourceName: String)

/**
 * The chapter region: the rendered rows (chapters interleaved with "N missing" separators) plus the
 * hidden-chapter view. Mirrors the novel's existing shape (type only), so the mapping is a rename, not a reshape.
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
    /** How a chapter the source dated nothing reads; see [UndatedChapterDate]. */
    val undatedChapterDate: UndatedChapterDate,
)

/**
 * One row in the neutral chapter list: a chapter or a "N missing chapters" separator. The neutral twin of
 * the manga `ChapterList.Item` / `ChapterList.MissingCount` and the novel [reikai.domain.novel.NovelChapterListEntry],
 * type only.
 */
sealed interface EntryChapterListItem {
    @Immutable
    data class Chapter(
        val id: Long,
        val name: String,
        /** The row's one subtitle line, as the reader's chapter list does it: in a merged group the
         *  source leads, then the chapter's scanlator. Null when there is neither. */
        val subtitle: String?,
        override val read: Boolean,
        override val bookmark: Boolean,
        val dateUpload: Long,
        val chapterNumber: Double,
        /** Where reading stopped, in the engine's own unit; null once read. */
        override val progress: ChapterProgress?,
        /** Resolved download state (queue state, else disk membership); the adapter does the resolution. */
        val downloadState: Download.State,
        /** Live download percent for the spinner; 0 for novels (no per-chapter progress). */
        val downloadProgress: Int,
        /** The number is out of line with its source's list ([reikai.domain.chapter.ChapterNumberHint]). */
        val numberHinted: Boolean,
    ) : EntryChapterListItem, ChapterMarks

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
 * screen's page selector sheet calls the novel model to re-run the chapter pipeline. Null for manga and
 * for single-page novels.
 */
@Immutable
data class NovelPageSelectorCapability(
    val pages: List<String>,
    val pageIndex: Int,
    val isPageLoading: Boolean,
)
