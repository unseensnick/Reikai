package yokai.presentation.details.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.bookmarkedFilter
import eu.kanade.tachiyomi.data.database.models.chapterOrder
import eu.kanade.tachiyomi.data.database.models.downloadedFilter
import eu.kanade.tachiyomi.data.database.models.hideChapterTitle
import eu.kanade.tachiyomi.data.database.models.readFilter
import eu.kanade.tachiyomi.data.database.models.sortDescending
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.recommendation.RECOMMENDS_SOURCE
import eu.kanade.tachiyomi.data.recommendation.RelatedMangasLoader
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.manga.MangaMergeManager
import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCandidate
import eu.kanade.tachiyomi.ui.manga.related.browse.RelatedMangasHandoff
import eu.kanade.tachiyomi.util.chapter.ChapterFilter
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.manga.MangaUtil
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchNonCancellableIO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.category.interactor.GetCategories
import yokai.domain.category.interactor.SetMangaCategories
import yokai.domain.chapter.ChapterAggregation
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.chapter.models.ChapterUpdate
import yokai.domain.library.custom.interactor.CreateCustomManga
import yokai.domain.library.custom.model.CustomMangaInfo
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.presentation.details.ManageSourceItem
import yokai.presentation.details.detailsLog

sealed interface MangaDetailsDialog {
    /** Categories picker shown when adding to library (or explicitly from overflow). */
    data class ChangeCategory(
        val manga: Manga,
        val allCategories: List<Category>,
        val currentCategoryIds: Set<Long>,
    ) : MangaDetailsDialog
    /** Edit title / author / artist / description / genre overrides. */
    data class EditMangaInfo(val manga: Manga) : MangaDetailsDialog
    /** Merge-group "Manage sources" panel: the grouped sources the user can split or remove. */
    data class ManageSources(val sources: List<ManageSourceItem>) : MangaDetailsDialog
}

/** One grouped source for the details source-view chip row. */
data class SourceTab(val mangaId: Long, val sourceName: String)

sealed interface MangaDetailsState {
    data object Loading : MangaDetailsState
    data class Loaded(
        val manga: Manga,
        val chapters: List<Chapter>,
        /**
         * Value-equality signature of the chapter list's content (id + read + bookmark + progress,
         * in order). [Chapter]'s own equals compares only by url, so without this the data class
         * (and any `remember` keyed on [chapters]) treats a read/bookmark change as "unchanged" and
         * the UI goes stale. Drives StateFlow emission and the row `remember` key.
         */
        val chapterStateHash: Int,
        /**
         * Value-equality signature of the manga's displayed fields (favorite, title, author, artist,
         * description, genre, status, cover). [Manga]'s own equals compares only by url+source, so
         * without this a favorite toggle or edit-info change leaves [manga] "equal" and StateFlow
         * dedups the emission, leaving the heart / header stale.
         */
        val mangaStateHash: Int,
        /** Next chapter to read (lowest unread); null when everything is read. Drives the FAB. */
        val resumeChapter: Chapter?,
        /** Any chapter read or partially read, so the FAB reads "Resume" instead of "Start reading". */
        val hasStarted: Boolean,
        /** Effective sort key (CHAPTER_SORTING_*), resolved against global default. */
        val sorting: Int,
        val sortDescending: Boolean,
        /** Effective filters (CHAPTER_SHOW_* / SHOW_ALL), resolved against global default. */
        val readFilter: Int,
        val downloadedFilter: Int,
        val bookmarkedFilter: Int,
        val hideChapterTitles: Boolean,
        /** Every scanlator across all chapters (unfiltered), for the scanlator picker. */
        val allScanlators: Set<String>,
        val filteredScanlators: Set<String>,
        /** Whether this manga's local sort/filter matches the global default (hides set/reset buttons). */
        val sortMatchesDefault: Boolean,
        val filterMatchesDefault: Boolean,
        /** Per-chapter download state, keyed by chapter id. Updated live from DownloadManager flows. */
        val downloads: Map<Long, DownloadInfo> = emptyMap(),
        /** Selected chapter ids; non-empty switches the top bar to the multi-select action mode. */
        val selection: Set<Long> = emptySet(),
        /** Currently visible modal dialog; null when none. */
        val dialog: MangaDetailsDialog? = null,
        /** Related-manga carousel slice (capped); empty until loaded. */
        val relatedMangas: List<RelatedMangaCandidate> = emptyList(),
        /** Size of the full ranked pool, for the "See all (N)" label (>= [relatedMangas] size). */
        val relatedMangasTotal: Int = 0,
        /** True while the related-mangas fetch is in flight, so the UI can show a skeleton. */
        val relatedMangasLoading: Boolean = false,
        /**
         * Ids of every manga in this title's merge group (including this one). Size > 1 means the
         * title is merged from multiple sources, which is what gates the "Manage sources" overflow
         * entry. Computed once on load and updated after a split / remove. Empty until computed.
         */
        val relatedMangaIds: List<Long> = emptyList(),
        /** The grouped sources for the source-view chip row; empty for a single-source title. */
        val sourceTabs: List<SourceTab> = emptyList(),
        /** Which source the chapter list is showing: null = the unified stitched list, otherwise a
         *  specific grouped source's own chapters. */
        val sourceView: Long? = null,
    ) : MangaDetailsState
    data object NotFound : MangaDetailsState
}

data class DownloadInfo(val state: Download.State, val progress: Int)

/**
 * ScreenModel for the manga details Compose port. State is driven reactively: a manga flow keyed by
 * id drives an inner chapters+scanlators subscription ([init]), so the screen auto-refreshes on any
 * DB change (favorite, read/bookmark, sort/filter, scanlator filter, new chapters from a library
 * update). Mutations just write to the DB and let the flow re-emit; the only exception is edit-info,
 * which lands in a separate table the manga query doesn't watch, so it triggers a one-shot [reload].
 * Mirrors [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter]'s pipeline without its runBlocking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MangaDetailsScreenModel(
    private val mangaId: Long,
) : StateScreenModel<MangaDetailsState>(MangaDetailsState.Loading), KoinComponent {

    private val getManga: GetManga by inject()
    private val getChapter: GetChapter by inject()
    private val updateChapter: UpdateChapter by inject()
    private val updateManga: UpdateManga by inject()
    private val chapterFilter: ChapterFilter by inject()
    private val preferences: PreferencesHelper by inject()
    private val downloadManager: DownloadManager by inject()
    private val sourceManager: SourceManager by inject()
    private val getCategories: GetCategories by inject()
    private val setMangaCategories: SetMangaCategories by inject()
    private val createCustomManga: CreateCustomManga by inject()
    private val insertManga: InsertManga by inject()
    private val relatedMangasLoader: RelatedMangasLoader by inject()
    private val relatedMangasHandoff: RelatedMangasHandoff by inject()

    /** Range-select anchors (first/last selected index in the displayed list), like Mihon. */
    private val selectedPositions = intArrayOf(-1, -1)

    /** Full unbounded ranked pool, held for the "See all" browse handoff (not part of UI state). */
    private var relatedMangasFullPool: List<RelatedMangaCandidate> = emptyList()

    /** One-shot gate so the carousel fetch runs once per screen, mirroring the legacy presenter. */
    private var relatedMangasFetched = false

    /** Shared merge / unmerge ops. Stateless (self-injects via Injekt), so a fresh instance is fine;
     *  mirrors the legacy presenter, which holds its own instance. */
    private val mergeManager = MangaMergeManager()

    /** One-shot gate for the group-id computation; [computeRelatedMangaIds] also heals the merge
     *  prefs, so it must run at most once per screen open. */
    private var relatedMangaIdsFetched = false

    /**
     * The current merge-group member ids (this manga + its grouped siblings), or empty until
     * resolved. Single source of truth that drives the chapter pipeline: when size > 1 the pipeline
     * fans out one chapter subscription per sibling and stitches them via [ChapterAggregation].
     * Updated by [loadRelatedMangaIds] on open and by [splitSources] / [removeSourcesFromLibrary].
     */
    private val groupIdsFlow = MutableStateFlow<List<Long>>(emptyList())

    /**
     * The latest per-sibling chapter lists (sibling manga id -> its chapters) backing the unified
     * list. Kept so the write-back in 6.3c can resolve every sibling row sharing a chapter number,
     * and so the per-source chip view can show one source's own chapters. For a non-merged title
     * this is just `{ mangaId -> chapters }`.
     */
    private var chaptersBySource: Map<Long, List<Chapter>> = emptyMap()

    /** Selected source for the chip row (null = unified). Folded into the chapter pipeline so a chip
     *  tap re-renders the list without re-fetching. */
    private val sourceViewFlow = MutableStateFlow<Long?>(null)

    /** Grouped-source chip data (source names), recomputed when the group resolves or changes. */
    private var sourceTabs: List<SourceTab> = emptyList()

    /** One emission of the chapter pipeline: the per-sibling map, the stitched list, and the union
     *  of scanlators across the group. */
    private data class ChapterData(
        val chaptersBySource: Map<Long, List<Chapter>>,
        val unified: List<Chapter>,
        val scanlators: List<String>,
    )

    init {
        // Reactive load: the manga flow drives an inner chapters+scanlators subscription so the
        // screen auto-refreshes on any DB change (favorite, read/bookmark, sort/filter, new chapters
        // from a library update). The outer collectLatest restarts the inner subscription when the
        // manga changes, recomputing the scanlator-filter flag (which the chapter query needs).
        screenModelScope.launchIO {
            getManga.subscribeById(mangaId).collectLatest { manga ->
                if (manga == null) {
                    detailsLog { "manga $mangaId not found" }
                    mutableState.value = MangaDetailsState.NotFound
                    return@collectLatest
                }
                // The group ids drive the chapter source set: a single id for a normal title, or
                // every sibling once the title is known to be merged. flatMapLatest re-subscribes
                // when the group changes (resolved on open, edited by split / remove).
                groupIdsFlow.flatMapLatest { group ->
                    if (group.size > 1) {
                        // Merged: fetch each sibling unfiltered and stitch. Per-sibling scanlator
                        // filters are skipped here so one source's filter can't drop another's
                        // chapters; the merged-set scanlator filter lands in 6.3d.
                        combine(
                            combine(group.map { getChapter.subscribeAll(it, filterScanlators = false) }) { it.toList() },
                            combine(group.map { getChapter.subscribeScanlators(it) }) { it.toList() },
                        ) { perSourceChapters, perSourceScanlators ->
                            val bySource = group.zip(perSourceChapters).toMap()
                            // Only offer / apply the scanlator filter when a source has a real group
                            // choice; one-per-source labels aren't filterable (the chips handle sources).
                            val hasScanlatorChoice = perSourceHasScanlatorChoice(perSourceScanlators)
                            val forUnified = if (hasScanlatorChoice) scanlatorFilteredForUnified(manga, bySource) else bySource
                            ChapterData(
                                chaptersBySource = bySource,
                                unified = stampMergedReadingOrder(ChapterAggregation.aggregate(forUnified)),
                                scanlators = if (hasScanlatorChoice) perSourceScanlators.flatten() else emptyList(),
                            )
                        }
                    } else {
                        // Single source: unchanged behavior, including the query-level scanlator filter.
                        val applyFilter = manga.filtered_scanlators?.isNotEmpty() == true
                        combine(
                            getChapter.subscribeAll(manga.id!!, applyFilter),
                            getChapter.subscribeScanlators(manga.id!!),
                        ) { chapters, scanlators ->
                            ChapterData(
                                chaptersBySource = mapOf(manga.id!! to chapters),
                                unified = chapters,
                                scanlators = scanlators,
                            )
                        }
                    }
                }
                    // Re-render when the chosen source view changes, without re-fetching chapters.
                    .combine(sourceViewFlow) { data, _ -> data }
                    .collectLatest { data ->
                        chaptersBySource = data.chaptersBySource
                        rebuildState(manga, data.unified, data.scanlators)
                    }
            }
        }
        observeDownloads()
    }

    /**
     * One-shot refresh from the DB. Called on screen resume (the Compose equivalent of the legacy
     * controller's `onActivityResumed` -> `fetchChapters`), which is how returning from the reader
     * Activity picks up read/bookmark changes: SQLDelight's flow notifications are unreliable for the
     * chapter query because it joins a view, so we don't depend on them for cross-Activity returns.
     */
    fun refresh() = reload()

    /**
     * One-shot reload, also used after an edit the reactive flow can't observe (custom manga info
     * lives in a separate table the `mangas` query doesn't join).
     */
    private fun reload() {
        screenModelScope.launchIO {
            val manga = getManga.awaitById(mangaId) ?: run {
                mutableState.value = MangaDetailsState.NotFound
                return@launchIO
            }
            val group = groupIdsFlow.value
            if (group.size > 1) {
                val bySource = group.associateWith { getChapter.awaitAll(it, filterScanlators = false) }
                chaptersBySource = bySource
                val perSourceScanlators = group.map { getChapter.awaitScanlators(it) }
                val hasScanlatorChoice = perSourceHasScanlatorChoice(perSourceScanlators)
                val forUnified = if (hasScanlatorChoice) scanlatorFilteredForUnified(manga, bySource) else bySource
                rebuildState(
                    manga,
                    stampMergedReadingOrder(ChapterAggregation.aggregate(forUnified)),
                    if (hasScanlatorChoice) perSourceScanlators.flatten() else emptyList(),
                )
            } else {
                val chapters = getChapter.awaitAll(manga, filterScanlators = null)
                chaptersBySource = mapOf(mangaId to chapters)
                rebuildState(manga, chapters, getChapter.awaitScanlators(mangaId))
            }
        }
    }

    /** Builds [MangaDetailsState.Loaded] from raw DB rows, preserving transient UI (selection, dialog). */
    private fun rebuildState(manga: Manga, unifiedChapters: List<Chapter>, allScanlatorsList: List<String>) {
        val sourceView = sourceViewFlow.value
        // The per-source chip view shows that one source's own (pristine) chapters; otherwise the
        // unified stitched list. Fall back to unified if a stale source id no longer resolves.
        val rawChapters = if (sourceView != null && chaptersBySource.size > 1) {
            chaptersBySource[sourceView] ?: unifiedChapters
        } else {
            unifiedChapters
        }
        val sort = ChapterSort(manga, chapterFilter, preferences)
        val sorted = sort.getChaptersSorted(rawChapters)
        val resume = sort.getNextUnreadChapter(rawChapters)
        val allScanlators = allScanlatorsList.filter { it.isNotBlank() }.toSet()
        val downloads = recomputeDownloads(manga, sorted, downloadManager.queueState.value)
        // Content signature: Chapter.equals is url-only, so fold the mutable read/bookmark/progress
        // (in display order) into a hash that distinguishes "same chapters, different read state".
        // manga_id is folded in too because a merged list mixes sources, and two different sources'
        // chapters can share a url; without it a cross-source change could hash-collide and be dropped.
        val chapterStateHash = sorted.fold(7) { acc, c ->
            (((((acc * 31 + (c.id ?: 0L).hashCode()) * 31 + (c.manga_id ?: 0L).hashCode()) * 31 + c.read.hashCode()) * 31 + c.bookmark.hashCode()) * 31 + c.last_page_read)
        }
        // Same trick for the manga: its equals is url+source only, so fold the displayed fields that
        // can change (favorite toggle, edit-info, cover) into a hash that distinguishes them.
        val mangaStateHash = listOf(
            manga.favorite,
            manga.title,
            manga.author,
            manga.artist,
            manga.description,
            manga.genre,
            manga.status,
            manga.thumbnail_url,
            manga.cover_last_modified,
        ).hashCode()
        val current = state.value as? MangaDetailsState.Loaded
        detailsLog { "rebuildState \"${manga.title}\" chapters=${sorted.size} resume=${resume?.name ?: "none"}" }
        mutableState.value = MangaDetailsState.Loaded(
            manga = manga,
            chapters = sorted,
            chapterStateHash = chapterStateHash,
            mangaStateHash = mangaStateHash,
            resumeChapter = resume,
            hasStarted = rawChapters.any { it.read || it.last_page_read > 0 },
            sorting = manga.chapterOrder(preferences),
            sortDescending = manga.sortDescending(preferences),
            readFilter = manga.readFilter(preferences),
            downloadedFilter = manga.downloadedFilter(preferences),
            bookmarkedFilter = manga.bookmarkedFilter(preferences),
            hideChapterTitles = manga.hideChapterTitle(preferences),
            allScanlators = allScanlators,
            filteredScanlators = ChapterUtil.getScanlators(manga.filtered_scanlators).toSet(),
            sortMatchesDefault = sortMatchesDefault(manga),
            filterMatchesDefault = filterMatchesDefault(manga),
            downloads = downloads,
            selection = current?.selection ?: emptySet(),
            dialog = current?.dialog,
            // Carousel state isn't rebuilt from the DB; carry it across re-emissions like selection.
            relatedMangas = current?.relatedMangas ?: emptyList(),
            relatedMangasTotal = current?.relatedMangasTotal ?: 0,
            relatedMangasLoading = current?.relatedMangasLoading ?: false,
            // Group membership is owned by groupIdsFlow (the chapter pipeline's source of truth), so
            // always reflect its current value rather than carrying stale state across re-emissions.
            relatedMangaIds = groupIdsFlow.value,
            sourceTabs = sourceTabs,
            sourceView = sourceView,
        )
    }

    /**
     * Normalize a merged list's reading order. `source_order` is per-source, so the default
     * "by source order" sort interleaves sources nonsensically (a sibling's chapter 1.5 landing next
     * to the trunk's chapter 5). Restamp it to follow chapter number, newest first (the usual source
     * convention), so the existing sort / resume / next-chapter logic reads the unified list as one
     * coherent series. The "by chapter number" and "by upload date" sorts ignore `source_order`, so
     * they're unaffected. Only called for merged titles; single-source lists keep their real order.
     */
    /**
     * Apply this title's scanlator filter to each source's chapters before they're stitched. Merged
     * sources are fetched unfiltered (so one source's filter can't drop another's chapters), so the
     * filter is applied here for the unified build, matching the SQL query's exact-name exclusion
     * (`chapters.scanlator = filtered_scanlators.name`). Filtering before aggregation lets gap-fill
     * pick a non-excluded copy when one source's representative is hidden. [chaptersBySource] stays
     * unfiltered so per-source views show raw source chapters and sibling propagation marks every row.
     */
    private fun scanlatorFilteredForUnified(manga: Manga, bySource: Map<Long, List<Chapter>>): Map<Long, List<Chapter>> {
        val excluded = ChapterUtil.getScanlators(manga.filtered_scanlators).toSet()
        if (excluded.isEmpty()) return bySource
        return bySource.mapValues { (_, chapters) -> chapters.filterNot { it.scanlator in excluded } }
    }

    /**
     * Whether the scanlator "Filter groups" option is meaningful for a merged title: only when at
     * least one single source carries more than one distinct scanlator (a real translation-group
     * choice). When the multiple scanlators are just one-per-source labels (e.g. "official",
     * "www.manganato.com"), filtering by them is pointless, so the picker is hidden and the filter
     * skipped, leaving source selection to the chips.
     */
    private fun perSourceHasScanlatorChoice(perSourceScanlators: List<List<String>>): Boolean =
        perSourceScanlators.any { it.filter(String::isNotBlank).distinct().size > 1 }

    private fun stampMergedReadingOrder(chapters: List<Chapter>): List<Chapter> =
        chapters.sortedWith(compareByDescending { it.chapter_number })
            // Copy before restamping so the shared per-source lists in [chaptersBySource] keep their
            // own native source_order (the per-source view relies on it); only the unified display
            // copies carry the synthetic chapter-number order.
            .mapIndexed { index, chapter -> chapter.copy().apply { source_order = index } }

    fun markAllRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            detailsLog { "markAllRead read=$read count=${loaded.chapters.size}" }
            applyRead(loaded.chapters, read)
        }
    }

    private suspend fun applyRead(chapters: List<Chapter>, read: Boolean) {
        val updates = expandToSiblings(chapters).mapNotNull { ch -> ch.id?.let { ChapterUpdate(id = it, read = read) } }
        if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
    }

    private suspend fun applyBookmark(chapters: List<Chapter>, bookmark: Boolean) {
        val updates = expandToSiblings(chapters).mapNotNull { ch -> ch.id?.let { ChapterUpdate(id = it, bookmark = bookmark) } }
        if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
    }

    /**
     * For a merged title, expand a set of chapters to every sibling row that shares each chapter's
     * recognized number, so a read / bookmark mark applied in the unified list lands on the same
     * chapter in every grouped source (the point of merging). No-op for a single-source title.
     *
     * Only read + bookmark propagate this way (callers build those updates); `last_page_read` is
     * never propagated since page counts differ across sources. Chapters with an unrecognized number
     * can't be matched, so they're passed through to mark only their own row.
     */
    private fun expandToSiblings(chapters: List<Chapter>): List<Chapter> {
        val sources = chaptersBySource
        if (sources.size <= 1) return chapters
        val numbers = chapters.asSequence()
            .filter { it.isRecognizedNumber }
            .map { it.chapter_number }
            .toHashSet()
        val matched = sources.values.asSequence()
            .flatten()
            .filter { it.isRecognizedNumber && it.chapter_number in numbers }
        val unmatchable = chapters.filterNot { it.isRecognizedNumber }
        return (matched + unmatchable).distinctBy { it.id }.toList()
    }

    fun setSortOrder(sort: Int, descend: Boolean) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "setSortOrder sort=$sort descend=$descend" }
            manga.setChapterOrder(sort, if (descend) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC)
            if (sortMatchesDefault(manga)) manga.setSortToGlobal()
            persistFlags(manga)
        }
    }

    fun setGlobalSort(sort: Int, descend: Boolean) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "setGlobalSort sort=$sort descend=$descend" }
            preferences.sortChapterOrder().set(sort)
            preferences.chaptersDescAsDefault().set(descend)
            manga.setSortToGlobal()
            persistFlags(manga)
        }
    }

    fun resetSortToDefault() {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "resetSortToDefault" }
            manga.setSortToGlobal()
            persistFlags(manga)
        }
    }

    fun setFilters(read: Int, downloaded: Int, bookmarked: Int) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "setFilters read=$read downloaded=$downloaded bookmarked=$bookmarked" }
            manga.readFilter = read
            manga.downloadedFilter = downloaded
            manga.bookmarkedFilter = bookmarked
            manga.setFilterToLocal()
            if (filterMatchesDefault(manga)) manga.setFilterToGlobal()
            persistFlags(manga)
        }
    }

    fun setGlobalFilters(read: Int, downloaded: Int, bookmarked: Int) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "setGlobalFilters read=$read downloaded=$downloaded bookmarked=$bookmarked" }
            preferences.filterChapterByRead().set(read)
            preferences.filterChapterByDownloaded().set(downloaded)
            preferences.filterChapterByBookmarked().set(bookmarked)
            preferences.hideChapterTitlesByDefault().set(manga.hideChapterTitles)
            manga.setFilterToGlobal()
            persistFlags(manga)
        }
    }

    fun resetFilterToDefault() {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "resetFilterToDefault" }
            manga.setFilterToGlobal()
            persistFlags(manga)
        }
    }

    fun setHideChapterTitles(hide: Boolean) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "setHideChapterTitles hide=$hide" }
            manga.displayMode = if (hide) Manga.CHAPTER_DISPLAY_NUMBER else Manga.CHAPTER_DISPLAY_NAME
            manga.setFilterToLocal()
            if (filterMatchesDefault(manga)) manga.setFilterToGlobal()
            persistFlags(manga)
        }
    }

    fun setScanlatorFilter(scanlators: Set<String>) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            val all = (state.value as? MangaDetailsState.Loaded)?.allScanlators.orEmpty()
            detailsLog { "setScanlatorFilter count=${scanlators.size}/${all.size}" }
            MangaUtil.setScanlatorFilter(updateManga, manga, if (scanlators.size == all.size) emptySet() else scanlators)
        }
    }

    /** Tap a chapter's download indicator: download when absent/errored, otherwise delete or cancel. */
    fun downloadAction(chapterId: Long) {
        screenModelScope.launchIO {
            val loaded = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            val manga = loaded.manga
            val chapter = loaded.chapters.find { it.id == chapterId } ?: return@launchIO
            val current = loaded.downloads[chapterId]?.state ?: Download.State.NOT_DOWNLOADED
            detailsLog { "downloadAction chapter=$chapterId state=$current" }
            when (current) {
                Download.State.NOT_DOWNLOADED, Download.State.ERROR -> {
                    applyDownloadState(chapterId, Download.State.QUEUE)
                    downloadManager.downloadChapters(manga, listOf(chapter))
                }
                else -> {
                    // deleteChapters(force) removes from the queue and deletes files in one call,
                    // so it covers both "delete a downloaded chapter" and "cancel a queued one".
                    val source = sourceManager.getOrStub(manga.source)
                    downloadManager.deleteChapters(listOf(chapter), manga, source, force = true)
                    applyDownloadState(chapterId, Download.State.NOT_DOWNLOADED)
                }
            }
        }
    }

    fun downloadNext(count: Int) = queueDownload { chapters, sort ->
        chapters.sortedWith(sort.sortComparator(ignoreAsc = true))
            .filter { !it.read }
            .distinctBy { it.name }
            .take(count)
    }

    fun downloadUnread() = queueDownload { chapters, _ -> chapters.filter { !it.read } }

    fun downloadAll() = queueDownload { chapters, _ -> chapters }

    private fun queueDownload(select: (notDownloaded: List<Chapter>, sort: ChapterSort) -> List<Chapter>) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            // Exclude already-downloaded chapters up front so a count-limited selection (e.g. "next 5")
            // counts five chapters that still need downloading, not five-minus-the-downloaded-ones.
            val notDownloaded = getChapter.awaitAll(manga, filterScanlators = false)
                .filterNot { downloadManager.isChapterDownloaded(it, manga) }
            val sort = ChapterSort(manga, chapterFilter, preferences)
            val targets = select(notDownloaded, sort)
            detailsLog { "queueDownload count=${targets.size}" }
            if (targets.isNotEmpty()) downloadManager.downloadChapters(manga, targets)
        }
    }

    private fun observeDownloads() {
        screenModelScope.launchIO {
            downloadManager.queueState.collectLatest { queue ->
                val loaded = state.value as? MangaDetailsState.Loaded ?: return@collectLatest
                mutableState.value = loaded.copy(downloads = recomputeDownloads(loaded.manga, loaded.chapters, queue))
            }
        }
        screenModelScope.launchIO {
            downloadManager.statusFlow()
                .filter { it.manga.id == mangaId }
                .collect { applyDownloadState(it.chapter.id ?: return@collect, it.status, it.progress) }
        }
        screenModelScope.launchIO {
            downloadManager.progressFlow()
                .filter { it.manga.id == mangaId }
                .collect { applyDownloadState(it.chapter.id ?: return@collect, it.status, it.progress) }
        }
    }

    private fun recomputeDownloads(manga: Manga, chapters: List<Chapter>, queue: List<Download>): Map<Long, DownloadInfo> =
        chapters.mapNotNull { chapter ->
            val id = chapter.id ?: return@mapNotNull null
            val queued = queue.find { it.chapter.id == id }
            val downloadState = when {
                downloadManager.isChapterDownloaded(chapter, manga) -> Download.State.DOWNLOADED
                queued != null -> queued.status
                else -> Download.State.NOT_DOWNLOADED
            }
            id to DownloadInfo(downloadState, queued?.progress ?: 0)
        }.toMap()

    private fun applyDownloadState(chapterId: Long, newState: Download.State, progress: Int = 0) {
        val loaded = state.value as? MangaDetailsState.Loaded ?: return
        mutableState.value = loaded.copy(
            downloads = loaded.downloads.toMutableMap().apply { put(chapterId, DownloadInfo(newState, progress)) },
        )
    }

    // --- Multi-select ---

    /**
     * Toggle one chapter's selection. On long-press, extends the selection to cover the span from
     * the previous anchor (range-select), mirroring Mihon's MangaScreenModel.toggleSelection.
     */
    fun toggleSelection(chapterId: Long, selected: Boolean, fromLongPress: Boolean) {
        val loaded = state.value as? MangaDetailsState.Loaded ?: return
        val chapters = loaded.chapters
        val index = chapters.indexOfFirst { it.id == chapterId }
        if (index < 0) return
        if ((chapterId in loaded.selection) == selected) return

        val newSelection = loaded.selection.toMutableSet()
        val firstSelection = loaded.selection.isEmpty()
        if (selected) newSelection.add(chapterId) else newSelection.remove(chapterId)

        if (selected && fromLongPress) {
            if (firstSelection) {
                selectedPositions[0] = index
                selectedPositions[1] = index
            } else {
                val range = when {
                    index < selectedPositions[0] -> (index + 1 until selectedPositions[0]).also { selectedPositions[0] = index }
                    index > selectedPositions[1] -> (selectedPositions[1] + 1 until index).also { selectedPositions[1] = index }
                    else -> IntRange.EMPTY
                }
                range.forEach { i -> chapters[i].id?.let { newSelection.add(it) } }
            }
        } else if (!fromLongPress) {
            if (!selected) {
                if (index == selectedPositions[0]) selectedPositions[0] = chapters.indexOfFirst { it.id in newSelection }
                else if (index == selectedPositions[1]) selectedPositions[1] = chapters.indexOfLast { it.id in newSelection }
            } else {
                if (index < selectedPositions[0]) selectedPositions[0] = index
                else if (index > selectedPositions[1]) selectedPositions[1] = index
            }
        }
        detailsLog { "toggleSelection chapter=$chapterId selected=$selected long=$fromLongPress size=${newSelection.size}" }
        mutableState.value = loaded.copy(selection = newSelection)
    }

    fun selectAll() {
        val loaded = state.value as? MangaDetailsState.Loaded ?: return
        resetSelectionAnchors()
        mutableState.value = loaded.copy(selection = loaded.chapters.mapNotNull { it.id }.toSet())
    }

    fun invertSelection() {
        val loaded = state.value as? MangaDetailsState.Loaded ?: return
        resetSelectionAnchors()
        val inverted = loaded.chapters.mapNotNull { it.id }.filterNot { it in loaded.selection }.toSet()
        mutableState.value = loaded.copy(selection = inverted)
    }

    fun clearSelection() {
        val loaded = state.value as? MangaDetailsState.Loaded ?: return
        resetSelectionAnchors()
        if (loaded.selection.isNotEmpty()) mutableState.value = loaded.copy(selection = emptySet())
    }

    private fun resetSelectionAnchors() {
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun markSelectedRead(read: Boolean) {
        screenModelScope.launchIO {
            val targets = selectedChapters() ?: return@launchIO
            detailsLog { "markSelectedRead read=$read count=${targets.size}" }
            applyRead(targets, read)
            clearSelection()
        }
    }

    fun bookmarkSelected(bookmark: Boolean) {
        screenModelScope.launchIO {
            val targets = selectedChapters() ?: return@launchIO
            detailsLog { "bookmarkSelected bookmark=$bookmark count=${targets.size}" }
            applyBookmark(targets, bookmark)
            clearSelection()
        }
    }

    fun downloadSelected() {
        screenModelScope.launchIO {
            val loaded = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            val targets = loaded.chapters
                .filter { it.id in loaded.selection }
                .filterNot { downloadManager.isChapterDownloaded(it, loaded.manga) }
            detailsLog { "downloadSelected count=${targets.size}" }
            if (targets.isNotEmpty()) downloadManager.downloadChapters(loaded.manga, targets)
            clearSelection()
        }
    }

    fun deleteSelected() {
        screenModelScope.launchIO {
            val loaded = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            val targets = loaded.chapters.filter { it.id in loaded.selection }
            detailsLog { "deleteSelected count=${targets.size}" }
            if (targets.isNotEmpty()) {
                val source = sourceManager.getOrStub(loaded.manga.source)
                downloadManager.deleteChapters(targets, loaded.manga, source, force = true)
                targets.forEach { ch -> ch.id?.let { applyDownloadState(it, Download.State.NOT_DOWNLOADED) } }
            }
            clearSelection()
        }
    }

    /** Mark every chapter before the earliest selected one (in reading order) as read/unread. */
    fun markPreviousRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            val sort = ChapterSort(loaded.manga, chapterFilter, preferences)
            val ascending = loaded.chapters.sortedWith(sort.sortComparator(ignoreAsc = true))
            val earliest = ascending.indexOfFirst { it.id in loaded.selection }
            if (earliest > 0) {
                detailsLog { "markPreviousRead read=$read count=$earliest" }
                applyRead(ascending.subList(0, earliest), read)
            }
            clearSelection()
        }
    }

    private fun selectedChapters(): List<Chapter>? {
        val loaded = state.value as? MangaDetailsState.Loaded ?: return null
        return loaded.chapters.filter { it.id in loaded.selection }
    }

    // --- Favorite / categories ---

    fun toggleFavorite() {
        screenModelScope.launchIO {
            val loaded = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            val manga = loaded.manga
            val id = manga.id ?: return@launchIO
            if (!manga.favorite) {
                // The manga flow re-emits favorite=true and rebuilds the heart; show the category
                // picker on top (rebuildState preserves the dialog across that re-emit).
                updateManga.await(MangaUpdate(id, favorite = true))
                val allCategories = getCategories.await().filter { (it.id ?: 0) > 0 }
                if (allCategories.isNotEmpty()) {
                    val currentIds = getCategories.awaitByMangaId(id)
                        .mapNotNull { it.id?.toLong() }
                        .toSet()
                    val current = state.value as? MangaDetailsState.Loaded ?: return@launchIO
                    mutableState.value = current.copy(
                        dialog = MangaDetailsDialog.ChangeCategory(current.manga, allCategories, currentIds),
                    )
                }
            } else {
                updateManga.await(MangaUpdate(id, favorite = false))
            }
        }
    }

    /** Show the categories picker for a manga that is already in the library. */
    fun showChangeCategoryDialog() {
        screenModelScope.launchIO {
            val loaded = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            val id = loaded.manga.id ?: return@launchIO
            val allCategories = getCategories.await().filter { (it.id ?: 0) > 0 }
            if (allCategories.isEmpty()) return@launchIO
            val currentIds = getCategories.awaitByMangaId(id)
                .mapNotNull { it.id?.toLong() }
                .toSet()
            mutableState.value = loaded.copy(
                dialog = MangaDetailsDialog.ChangeCategory(loaded.manga, allCategories, currentIds),
            )
        }
    }

    fun moveMangaToCategoriesAndAddToLibrary(categoryIds: List<Long>) {
        screenModelScope.launchIO {
            val loaded = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            setMangaCategories.await(loaded.manga.id, categoryIds)
            // Categories live in a separate table the details screen doesn't display; just close.
            dismissDialog()
        }
    }

    // --- Edit manga info ---

    fun showEditMangaInfoDialog() {
        val loaded = state.value as? MangaDetailsState.Loaded ?: return
        mutableState.value = loaded.copy(dialog = MangaDetailsDialog.EditMangaInfo(loaded.manga))
    }

    fun updateMangaInfo(title: String?, author: String?, artist: String?, description: String?, genre: String?) {
        screenModelScope.launchIO {
            val loaded = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            val id = loaded.manga.id ?: return@launchIO
            createCustomManga.await(
                CustomMangaInfo(
                    mangaId = id,
                    title = title,
                    author = author,
                    artist = artist,
                    description = description,
                    genre = genre,
                ),
            )
            dismissDialog()
            // custom_manga_info is a separate table the manga flow doesn't watch, so refresh by hand.
            reload()
        }
    }

    // --- Dialog ---

    fun dismissDialog() {
        val loaded = state.value as? MangaDetailsState.Loaded ?: return
        mutableState.value = loaded.copy(dialog = null)
    }

    // --- Related mangas ---

    /**
     * One-shot: collect [RelatedMangasLoader] and fold its progressive emissions into state. The
     * loader serves a cached pool instantly then refreshes a stale one; [relatedMangasFetched]
     * keeps this to a single collection per screen, mirroring the legacy presenter's gate.
     */
    fun loadRelatedMangas() {
        if (relatedMangasFetched) return
        val manga = currentManga() ?: return
        val catalogueSource = sourceManager.getOrStub(manga.source) as? CatalogueSource ?: return
        if (catalogueSource.disableRelatedMangas) return
        relatedMangasFetched = true
        relatedMangasLoader.load(manga, catalogueSource)
            .onEach { result ->
                relatedMangasFullPool = result.fullPool
                val loaded = state.value as? MangaDetailsState.Loaded ?: return@onEach
                mutableState.value = loaded.copy(
                    relatedMangas = result.carousel,
                    relatedMangasTotal = result.fullPool.size,
                    relatedMangasLoading = result.loading,
                )
            }
            .launchIn(screenModelScope)
    }

    /** Provenance label for a carousel card: the tracker name for tracker-origin entries (sourceId
     *  == [RECOMMENDS_SOURCE]), otherwise the source's display name. */
    fun relatedProvenanceLabel(candidate: RelatedMangaCandidate): String =
        candidate.trackerName ?: sourceManager.getOrStub(candidate.sourceId).name

    /**
     * Resolve an installed-source related card to a local manga id (creating the DB row if it
     * doesn't exist yet) so the screen can open its details. Mirrors the legacy controller's
     * `toLocalManga` path. Returns null for tracker-origin cards ([RECOMMENDS_SOURCE]); those route
     * to global search instead.
     */
    suspend fun relatedToLocalId(candidate: RelatedMangaCandidate): Long? {
        if (candidate.sourceId == RECOMMENDS_SOURCE) return null
        getManga.awaitByUrlAndSource(candidate.manga.url, candidate.sourceId)?.let { return it.id }
        val newManga = try {
            Manga.create(candidate.manga.url, candidate.manga.title, candidate.sourceId)
        } catch (_: UninitializedPropertyAccessException) {
            return null
        }
        newManga.copyFrom(candidate.manga)
        newManga.id = insertManga.await(newManga)
        return newManga.id
    }

    /** Deposit the full ranked pool so the "See all" browse view can pick it up (keyed by manga id). */
    fun stageBrowseHandoff() {
        relatedMangasHandoff.put(mangaId, relatedMangasFullPool)
    }

    // --- Source grouping (merge / unmerge) ---

    /**
     * One-shot: resolve this title's merge group and store the member ids in state. Gated because
     * [MangaMergeManager.computeRelatedMangaIds] also heals corrupted merge prefs as a side effect,
     * so it must run at most once per open (mirrors the legacy presenter's dedup window).
     */
    fun loadRelatedMangaIds() {
        if (relatedMangaIdsFetched) return
        val manga = currentManga() ?: return
        relatedMangaIdsFetched = true
        screenModelScope.launchIO {
            val result = mergeManager.computeRelatedMangaIds(mangaId, manga.title)
            detailsLog { "loadRelatedMangaIds group=${result.ids.size} cleaned=${result.cleanupCount}" }
            updateGroup(result.ids.toList())
        }
    }

    /**
     * Apply a new merge-group membership: refresh the source chips, reset the view to unified, then
     * publish the ids so the chapter pipeline re-aggregates. Source tabs are set before the ids so
     * the rebuild triggered by [groupIdsFlow] already sees them.
     */
    private suspend fun updateGroup(ids: List<Long>) {
        sourceTabs = computeSourceTabs(ids)
        sourceViewFlow.value = null
        groupIdsFlow.value = ids
    }

    private suspend fun computeSourceTabs(group: List<Long>): List<SourceTab> {
        if (group.size <= 1) return emptyList()
        return group.mapNotNull { id ->
            val m = getManga.awaitById(id) ?: return@mapNotNull null
            SourceTab(mangaId = id, sourceName = sourceManager.getOrStub(m.source).name)
        }
    }

    /** Switch the chapter list between the unified view (null) and a single grouped source. Clears
     *  any active selection since the chapter set changes. */
    fun setSourceView(mangaId: Long?) {
        if (sourceViewFlow.value == mangaId) return
        clearSelection()
        sourceViewFlow.value = mangaId
    }

    /**
     * The [Manga] a chapter actually belongs to, so the reader opens against the source that has it.
     * For a single-source title (or a primary-source chapter in a merged list) this is just the
     * current manga; a gap-filled chapter from a sibling resolves to that sibling's manga.
     */
    suspend fun mangaForChapter(chapter: Chapter): Manga? {
        val ownerId = chapter.manga_id ?: return currentManga()
        return if (ownerId == mangaId) currentManga() else getManga.awaitById(ownerId)
    }

    /** Resolve the grouped sources and open the Manage sources dialog. */
    fun showManageSourcesDialog() {
        screenModelScope.launchIO {
            val sources = mergeManager.availableSources(mangaId, groupIdsFlow.value.toLongArray())
            val items = sources.map { (id, source) ->
                ManageSourceItem(mangaId = id, sourceName = source.name, isCurrent = id == mangaId)
            }
            val current = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            mutableState.value = current.copy(dialog = MangaDetailsDialog.ManageSources(items))
        }
    }

    /** Split [targetIds] out of the merge group (they stay in the library, just ungrouped). */
    fun splitSources(targetIds: List<Long>) {
        if (targetIds.isEmpty()) { dismissDialog(); return }
        screenModelScope.launchIO {
            val newIds = mergeManager.removeFromGroup(groupIdsFlow.value.toLongArray(), targetIds)
            detailsLog { "splitSources removed=${targetIds.size} remaining=${newIds.size}" }
            // Re-aggregates the chapter list off the smaller group; rebuildState refreshes the gate.
            updateGroup(newIds.toList())
            dismissDialog()
        }
    }

    /**
     * Remove [targetIds] from the library: unfavorite + invalidate tracker reconciliation (awaited),
     * then delete covers / downloads / tracks in a non-cancellable scope so a mid-run back-out still
     * finishes the cleanup. Mirrors the legacy presenter's removeFromLibrary.
     */
    fun removeSourcesFromLibrary(targetIds: List<Long>) {
        if (targetIds.isEmpty()) { dismissDialog(); return }
        screenModelScope.launchIO {
            mergeManager.unfavoriteAndReconcile(targetIds)
            detailsLog { "removeSourcesFromLibrary count=${targetIds.size}" }
            val targetSet = targetIds.toSet()
            // Drop the removed sources from the group so the pipeline re-aggregates without them.
            updateGroup(groupIdsFlow.value.filterNot { it in targetSet })
            dismissDialog()
            screenModelScope.launchNonCancellableIO { mergeManager.cleanupRemoved(targetIds) }
        }
    }

    // --- Share / WebView ---

    /** Returns the manga's source URL for share and WebView actions; null for local/stub sources. */
    fun getMangaUrl(): String? {
        val manga = currentManga() ?: return null
        val source = sourceManager.getOrStub(manga.source) as? HttpSource ?: return null
        return runCatching { source.getMangaUrl(manga) }.getOrNull()
    }

    fun isHttpSource(): Boolean {
        val manga = currentManga() ?: return false
        return sourceManager.getOrStub(manga.source) is HttpSource
    }

    /** Display name of the manga's source, for the header info block. */
    fun sourceName(): String {
        val manga = currentManga() ?: return ""
        return sourceManager.getOrStub(manga.source).name
    }

    /** True when the source isn't installed (a stub), so the header can flag it. */
    fun isStubSource(): Boolean {
        val manga = currentManga() ?: return false
        return sourceManager.getOrStub(manga.source) is SourceManager.StubSource
    }

    fun skipPreMigration(): Boolean = preferences.skipPreMigration().get()

    private fun currentManga(): Manga? = (state.value as? MangaDetailsState.Loaded)?.manga

    private suspend fun persistFlags(manga: Manga) {
        // Writing chapter_flags to the mangas table re-emits the manga flow, which rebuilds state.
        updateManga.await(MangaUpdate(manga.id!!, chapterFlags = manga.chapter_flags))
    }

    private fun sortMatchesDefault(manga: Manga): Boolean =
        (
            manga.sortDescending == preferences.chaptersDescAsDefault().get() &&
                manga.sorting == preferences.sortChapterOrder().get()
            ) || !manga.usesLocalSort

    private fun filterMatchesDefault(manga: Manga): Boolean =
        (
            manga.readFilter == preferences.filterChapterByRead().get() &&
                manga.downloadedFilter == preferences.filterChapterByDownloaded().get() &&
                manga.bookmarkedFilter == preferences.filterChapterByBookmarked().get() &&
                manga.hideChapterTitles == preferences.hideChapterTitlesByDefault().get()
            ) || !manga.usesLocalFilter
}
