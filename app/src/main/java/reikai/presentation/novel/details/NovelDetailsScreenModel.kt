package reikai.presentation.novel.details

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import reikai.data.novel.NovelStatusCode
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.data.novel.toNovel
import reikai.data.novel.walkNovelPages
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelEditFlags
import reikai.domain.novel.model.effectiveBookmarkedFilter
import reikai.domain.novel.model.effectiveHideChapterTitles
import reikai.domain.novel.model.effectiveReadFilter
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import reikai.domain.novel.model.mergeRefreshedNovel
import reikai.domain.novel.model.setEditedFlag
import reikai.domain.novel.model.setNovelFlag
import reikai.domain.novel.model.sortedAndFiltered
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.data.Database
import tachiyomi.domain.manga.model.MangaCover
import uy.kohesive.injekt.injectLazy

/**
 * Single-source light-novel details state holder, the B1 port of Yōkai's `NovelDetailsScreenModel`
 * re-typed onto the S1 repos. DB-first: the stored novel + its chapters drive the screen; the source
 * is hit only on first open (no local chapters) or an explicit [refresh]. Owns favorite, categories,
 * edit-info, chapter sort/filter/display, multi-select read/bookmark, and the cover-tint seed.
 *
 * Merge (the [NovelDetailsState.Loaded.displayNovel] seam), the reader (chapter tap), and downloads
 * are stubbed here and wired at S8 / S4 / S5.
 */
class NovelDetailsScreenModel(
    private val sourceId: String,
    private val novelUrl: String,
) : StateScreenModel<NovelDetailsState>(NovelDetailsState.Loading) {

    private val novelRepo: NovelRepository by injectLazy()
    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val database: Database by injectLazy()
    private val downloadManager: NovelDownloadManager by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelCategories: SetNovelCategories by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    private val uiPreferences: UiPreferences by injectLazy()
    private val mergeManager: NovelMergeManager by injectLazy()
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()

    /** Resolved once the plugin host loads it; source-dependent ops defer until set. */
    @Volatile
    private var source: NovelSource? = null

    /** A first-open fetch (no stored chapters) runs at most once. */
    private var firstFetchTried = false
    private var refreshJob: Job? = null
    private var seedExtracted = false

    /** The opened novel's id (the merge "current" source); set once the anchor resolves. */
    @Volatile
    private var anchorNovelId = -1L

    /** The page (index into [NovelDetailsState.Loaded.pages]) the chapter list is showing. */
    private val pageIndex = MutableStateFlow(0)

    /** Merge-group novel ids (this novel + grouped siblings); 0/1 element when not merged. */
    private val relatedNovelIds = MutableStateFlow(longArrayOf())

    /** The grouped source chip the user is viewing; null = the unified ("All") list. */
    private val selectedSourceNovelId = MutableStateFlow<Long?>(null)

    /** novelId -> resolved source for every grouped sibling (unified rank, chips, reader routing). */
    private val siblingSources = MutableStateFlow<Map<Long, NovelSource>>(emptyMap())

    /** Paged keys already lazily fetched, so an empty page doesn't re-fetch on every flow emission. */
    private val triedPages = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Range-select anchor into the displayed chapter order; -1 when no selection. */
    private val selectionAnchor = intArrayOf(-1, -1)

    init {
        observeMergeGroup()
        observeMergeSourceChips()
        observeChapters()
        observeDownloadQueue()
        resolveSource()
    }

    /** Mirror the live download queue into [NovelDetailsState.Loaded.downloadStates]. Only the active
     *  queue states (queued/downloading/error) live here; a finished download is read from the
     *  chapter row's `isDownloaded` flag instead (see the chapter list's downloadStateProvider). */
    private fun observeDownloadQueue() {
        screenModelScope.launchIO {
            downloadManager.queueState.collectLatest { queue ->
                val map = queue.associate { it.chapterId to it.state.toDownloadState() }
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(downloadStates = map) ?: it }
            }
        }
    }

    private fun resolveSource() {
        screenModelScope.launchIO {
            try {
                installer.ensureLoaded()
            } catch (_: Throwable) {}
            val resolved = sourceManager.get(sourceId)
            if (resolved == null) {
                if (state.value !is NovelDetailsState.Loaded) {
                    mutableState.value = NovelDetailsState.Failed("Source not installed: $sourceId")
                }
            } else {
                source = resolved
                mutableState.update {
                    (it as? NovelDetailsState.Loaded)?.copy(sourceName = resolved.name, sourceUrl = resolved.site) ?: it
                }
                val loaded = state.value as? NovelDetailsState.Loaded
                if (loaded == null || loaded.chapters.isEmpty()) maybeFirstFetch(loaded?.novel)
            }
        }
    }

    private data class ChapterInputs(
        val anchor: Novel?,
        val related: LongArray,
        val selected: Long?,
        val pageIndex: Int,
        val sources: Map<Long, NovelSource>,
    )

    // DB-first: the stored anchor novel + the resolved merge group drive the chapter list. The unified
    // ("All") view pools every grouped source's chapters into one list (no page bar); a selected source
    // chip (or a non-merged novel) keeps its own per-page lazy list (the S3c/S3d behavior). A change to
    // chapterFlags / page / group / selection re-runs this via the combine.
    private fun observeChapters() {
        screenModelScope.launchIO {
            combine(
                novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId),
                relatedNovelIds,
                selectedSourceNovelId,
                pageIndex,
                siblingSources,
            ) { anchor, related, selected, idx, sources -> ChapterInputs(anchor, related, selected, idx, sources) }
                .collectLatest { (anchor, related, selected, idx, sources) ->
                    if (anchor == null) {
                        maybeFirstFetch(null)
                        return@collectLatest
                    }
                    if (related.size > 1 && selected == null) {
                        observeUnifiedChapters(anchor, related, sources)
                    } else {
                        observeSingleChapters(anchor, selected, idx)
                    }
                }
        }
    }

    /** Resolve the merge group whenever the anchor or the merge prefs change (the author guard is
     *  re-applied each time = metadata healing). Drives [relatedNovelIds]; chips + chapters react. */
    private fun observeMergeGroup() {
        screenModelScope.launchIO {
            combine(
                novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId),
                reikaiLibraryPreferences.novelManualMerges.changes(),
                reikaiLibraryPreferences.novelManualUnmerges.changes(),
                reikaiLibraryPreferences.novelAutoMergeSameTitle.changes(),
                reikaiLibraryPreferences.novelAutoMergeRequireAuthor.changes(),
            ) { anchor, _, _, _, _ -> anchor }
                .collectLatest { anchor ->
                    if (anchor == null) return@collectLatest
                    anchorNovelId = anchor.id
                    relatedNovelIds.value = mergeManager.computeRelatedNovelIds(anchor.id, anchor.title, anchor.author)
                }
        }
    }

    /** Resolve each grouped source + build the switcher chips whenever the group changes. */
    private fun observeMergeSourceChips() {
        screenModelScope.launchIO {
            relatedNovelIds.collectLatest { ids ->
                if (ids.size <= 1) {
                    siblingSources.value = emptyMap()
                    mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(mergeSources = emptyList()) ?: it }
                    return@collectLatest
                }
                runCatching { installer.ensureLoaded() }
                val resolved = HashMap<Long, NovelSource>()
                val chips = mutableListOf<NovelMergeSourceInfo>()
                for (id in ids) {
                    val novel = novelRepo.getById(id) ?: continue
                    val src = sourceManager.get(novel.source)
                    if (src != null) resolved[id] = src
                    chips += NovelMergeSourceInfo(id, src?.name ?: novel.source, id == anchorNovelId)
                }
                siblingSources.value = resolved
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(mergeSources = chips) ?: it }
            }
        }
    }

    /** Unified ("All") view: pool every grouped source's chapters into one aggregated, reading-ordered
     *  list (no pagination, pages don't align across sources). Each chapter keeps its own novelId. */
    private suspend fun observeUnifiedChapters(anchor: Novel, related: LongArray, sources: Map<Long, NovelSource>) {
        val flows = related.map { id -> chapterRepo.getByNovelIdAsFlow(id).map { id to it } }
        combine(flows) { pairs -> pairs.toMap() }.collectLatest { byNovel ->
            val sourceIdByNovel = byNovel.keys.associateWith { id -> sources[id]?.id.orEmpty() }
            val aggregated = NovelChapterAggregation.aggregate(
                byNovel,
                sourceIdByNovel,
                reikaiLibraryPreferences.preferredNovelSources.get(),
            )
            rebuildLoaded(anchor, anchor, restampReadingOrder(aggregated), emptyList(), 0)
        }
    }

    /** Single-source view: the anchor (non-merged or its own chip) or a selected sibling, with that
     *  novel's own per-page lazy list. Auto-fetch only runs for the anchor (its [source] is resolved);
     *  a selected sibling shows what's stored until a refresh-all (slice D) fills it. */
    private suspend fun observeSingleChapters(anchor: Novel, selected: Long?, idx: Int) {
        val isAnchorView = selected == null || selected == anchor.id
        val viewNovel = if (isAnchorView) anchor else (novelRepo.getById(selected!!) ?: anchor)
        val pages = computePages(viewNovel)
        if (pages.isNotEmpty() && idx >= pages.size) {
            pageIndex.value = 0
            return
        }
        val pageKey = pages.getOrNull(idx)
        val chapterFlow = if (pageKey == null) {
            chapterRepo.getByNovelIdAsFlow(viewNovel.id)
        } else {
            chapterRepo.getByNovelIdAndPageAsFlow(viewNovel.id, pageKey)
        }
        chapterFlow.collectLatest { chapters ->
            rebuildLoaded(anchor, viewNovel, chapters, pages, idx)
            if (chapters.isEmpty() && isAnchorView) {
                if (pageKey == null) maybeFirstFetch(viewNovel) else maybeFetchPage(viewNovel, pageKey)
            }
        }
    }

    /** Page keys for the selector: "1".."N" for a `parsePage` source, distinct volume labels for a
     *  label-grouped one, or empty (single unpaged list, no selector). */
    private suspend fun computePages(novel: Novel): List<String> = when {
        novel.totalPages > 1L -> (1..novel.totalPages).map { it.toString() }
        else -> chapterRepo.getDistinctPages(novel.id).takeIf { it.size > 1 } ?: emptyList()
    }

    /** Build [NovelDetailsState.Loaded] from the [anchor] (identity, favorite, chapter-view flags) and
     *  the [viewNovel] whose metadata + source the header shows (== anchor for the unified view, the
     *  selected sibling otherwise). Sort/filter always follow the anchor's flags. */
    private fun rebuildLoaded(
        anchor: Novel,
        viewNovel: Novel,
        chapters: List<NovelChapter>,
        pages: List<String>,
        pageIndex: Int,
    ) {
        val display = chapters.sortedAndFiltered(anchor, novelPreferences)
        val resume = chapters.sortedBy { it.sourceOrder }.firstOrNull { !it.read }
        val viewSource = siblingSources.value[viewNovel.id]
        mutableState.update { prev ->
            val loaded = prev as? NovelDetailsState.Loaded
            NovelDetailsState.Loaded(
                novel = anchor,
                displayNovel = viewNovel,
                chapters = display,
                pages = pages,
                pageIndex = if (pages.isEmpty()) 0 else pageIndex.coerceIn(0, pages.lastIndex),
                isPageLoading = loaded?.isPageLoading ?: false,
                isRefreshing = loaded?.isRefreshing ?: false,
                downloadStates = loaded?.downloadStates.orEmpty(),
                dialog = loaded?.dialog,
                selection = loaded?.selection.orEmpty().filterTo(HashSet()) { id -> chapters.any { it.id == id } },
                resumeChapter = resume,
                hasStarted = chapters.any { it.read },
                seedColor = loaded?.seedColor,
                sourceName = viewSource?.name ?: source?.name ?: loaded?.sourceName ?: sourceId,
                sourceUrl = viewSource?.site ?: source?.site ?: loaded?.sourceUrl,
                sorting = anchor.effectiveSorting(novelPreferences),
                sortDescending = anchor.effectiveSortDescending(novelPreferences),
                readFilter = anchor.effectiveReadFilter(novelPreferences),
                bookmarkedFilter = anchor.effectiveBookmarkedFilter(novelPreferences),
                hideChapterTitles = anchor.effectiveHideChapterTitles(novelPreferences),
                mergeSources = loaded?.mergeSources.orEmpty(),
                selectedSourceNovelId = selectedSourceNovelId.value,
            )
        }
        updateSeedColor(viewNovel)
    }

    /** Extract the cover's vibrant color once and seed the header tint. Keyed by `-novel.id` so the
     *  shared (manga) color cache never collides a novel with a same-id manga. */
    private fun updateSeedColor(novel: Novel) {
        if (seedExtracted || !uiPreferences.themeCoverBased.get()) return
        val url = novel.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return
        if (novel.id <= 0L) return
        seedExtracted = true
        screenModelScope.launchIO {
            val cover = MangaCover(
                mangaId = -novel.id,
                sourceId = 0L,
                isMangaFavorite = false,
                url = url,
                lastModified = novel.coverLastModified,
            )
            MangaCoverMetadata.setVibrantColor(cover)
            cover.vibrantCoverColor?.let { color ->
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(seedColor = Color(color)) ?: it }
            }
        }
    }

    private fun maybeFirstFetch(existing: Novel?) {
        if (firstFetchTried) return
        val src = source ?: return // defer until resolveSource sets it
        firstFetchTried = true
        screenModelScope.launchIO {
            runCatching { fetchAndSync(src, existing) }.onFailure { e ->
                if (state.value !is NovelDetailsState.Loaded) {
                    mutableState.value = NovelDetailsState.Failed(e.message ?: "Failed to load novel")
                }
            }
        }
    }

    /** parseNovel + persist metadata (edit-lock + blank safe) + sync the first page's chapters. The
     *  reactive flow then re-emits the updated novel/chapter list. A novel opened from Browse is
     *  inserted non-favorite. Returns the persisted novel (carries the refreshed `totalPages`). */
    private suspend fun fetchAndSync(src: NovelSource, existing: Novel?): Novel? {
        val sourceNovel = src.parseNovel(existing?.url ?: novelUrl)
        val target = if (existing != null) {
            val parsed = sourceNovel.toNovel(sourceId = src.id, favorite = existing.favorite)
            val merged = mergeRefreshedNovel(existing, parsed)
            if (merged != existing) novelRepo.update(merged)
            merged
        } else {
            // Non-favorite shadow row so a browse-opened novel is viewable without being silently
            // added; insertOrGet reuses a concurrently-created row instead of duplicating.
            novelRepo.insertOrGet(sourceNovel.toNovel(sourceId = src.id, favorite = false)) ?: return null
        }
        val chapters = sourceNovel.chapters.orEmpty()
        if (chapters.isNotEmpty()) {
            // A paged source's first page is page "1"; tag it so the page-"1" query finds these rows.
            val pageTag = if (sourceNovel.totalPages > 1) "1" else null
            syncChaptersWithNovelSource(chapters, target, chapterRepo, novelRepo, database, page = pageTag)
        }
        return target
    }

    /** Lazily fetch a paged source's page when it has no stored rows yet. Skipped while a filter is
     *  active (0 rows may just mean the filter hid them, not that the page is unfetched) and once a
     *  page has been tried (an empty page must not re-fetch on every emission). */
    private fun maybeFetchPage(novel: Novel, pageKey: String) {
        val src = source ?: return
        val loaded = state.value as? NovelDetailsState.Loaded
        if (loaded != null && (loaded.readFilter != 0L || loaded.bookmarkedFilter != 0L)) return
        if (!triedPages.add(pageKey)) return
        screenModelScope.launchIO {
            mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isPageLoading = true) ?: it }
            try {
                src.parsePage(novel.url, pageKey)?.chapters?.takeIf { it.isNotEmpty() }?.let {
                    syncChaptersWithNovelSource(it, novel, chapterRepo, novelRepo, database, page = pageKey)
                }
            } catch (_: Throwable) {
            } finally {
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isPageLoading = false) ?: it }
            }
        }
    }

    fun selectPage(index: Int) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        if (index < 0 || index >= loaded.pages.size || index == loaded.pageIndex) return
        clearSelection()
        pageIndex.value = index
        dismissDialog()
    }

    fun showPageSelectorDialog() = updateLoaded { it.copy(dialog = NovelDetailsDialog.PageSelector) }

    // --- Merge: source switcher + split ---

    /** Switch the chapter view between the unified list (null) and a single grouped source's list. */
    fun selectSource(novelId: Long?) {
        if (selectedSourceNovelId.value == novelId) return
        clearSelection()
        pageIndex.value = 0
        selectedSourceNovelId.value = novelId
    }

    /** Split the given grouped sources out (subset split; full dissolve if they cover the group). The
     *  pref write re-resolves the group; the value is also set directly for instant feedback. */
    fun splitSources(targetIds: List<Long>) {
        if (targetIds.isEmpty()) return
        selectedSourceNovelId.value = null
        relatedNovelIds.value = mergeManager.splitOrDissolve(relatedNovelIds.value, targetIds)
    }

    /** Renumber sourceOrder over the unified list (ascending by chapter number = reading order) so a
     *  "by source order" sort doesn't interleave sources. Copies; each source's own order is untouched. */
    private fun restampReadingOrder(chapters: List<NovelChapter>): List<NovelChapter> =
        chapters.sortedBy { it.chapterNumber }.mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) }

    /** Stale-then-fresh: the cached list stays under a spinner while the sync runs, then the flow
     *  swaps in fresh rows. Read/bookmark are preserved by the sync. Deduped against concurrent runs.
     *
     *  For a paged source: parseNovel refreshes page 1 + the page count, [walkNovelPages] re-fetches
     *  the previously-last page through any newly-opened ones, and the page the user is viewing is
     *  re-fetched too if the walk didn't already cover it. Bounded, never a full fetch-all. */
    fun refresh() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val anchorSrc = source ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = screenModelScope.launchIO {
            mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = true) ?: it }
            try {
                // Refresh the anchor first (its refreshed novel drives the viewed-page fix below), then
                // every other grouped source so the unified list picks up new chapters everywhere.
                val anchorUpdated = refreshNovel(anchorSrc, loaded.novel)
                for (id in relatedNovelIds.value) {
                    if (id == loaded.novel.id) continue
                    val novel = novelRepo.getById(id) ?: continue
                    val src = siblingSources.value[id] ?: continue
                    refreshNovel(src, novel)
                }
                // Force-refresh the viewed page when viewing the anchor's own (paged) list and the walk
                // skipped it (a middle page); the unified view has no pages so this is a no-op there.
                if (loaded.selectedSourceNovelId == null || loaded.selectedSourceNovelId == loaded.novel.id) {
                    forceRefreshViewedPage(loaded, anchorUpdated, anchorSrc)
                }
            } finally {
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = false) ?: it }
            }
        }
    }

    /** parseNovel + sync page 1, then walk the previously-last page through any newly-opened ones.
     *  Bounded, never a full fetch-all. Returns the refreshed novel (carries the new totalPages). */
    private suspend fun refreshNovel(src: NovelSource, novel: Novel): Novel {
        val oldTotalPages = novel.totalPages
        val updated = runCatching { fetchAndSync(src, novel) }.getOrNull() ?: novel
        if (updated.totalPages > 1L) {
            walkNovelPages(updated, src, maxOf(2L, oldTotalPages), updated.totalPages, chapterRepo, novelRepo, database)
        }
        return updated
    }

    private suspend fun forceRefreshViewedPage(loaded: NovelDetailsState.Loaded, updated: Novel, src: NovelSource) {
        val newTotalPages = updated.totalPages
        if (newTotalPages <= 1L) return
        val walkFrom = maxOf(2L, loaded.novel.totalPages)
        val curPage = loaded.pages.getOrNull(loaded.pageIndex)?.toLongOrNull() ?: return
        if (curPage > 1L && curPage !in walkFrom..newTotalPages) {
            val key = curPage.toString()
            runCatching {
                src.parsePage(updated.url, key)?.chapters?.takeIf { it.isNotEmpty() }?.let {
                    syncChaptersWithNovelSource(it, updated, chapterRepo, novelRepo, database, page = key)
                }
            }
        }
    }

    // --- Favorite / categories ---

    fun toggleFavorite() {
        screenModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            if (!novel.favorite) {
                novelRepo.update(novel.copy(favorite = true, dateAdded = System.currentTimeMillis()))
                val categories = getNovelCategories.await().filter { it.id > 0L }
                if (categories.isNotEmpty()) {
                    val current = getNovelCategories.awaitByNovelId(novel.id).map { it.id }.toSet()
                    updateLoaded { it.copy(dialog = NovelDetailsDialog.ChangeCategory(categories, current)) }
                }
            } else {
                novelRepo.update(novel.copy(favorite = false))
            }
        }
    }

    fun showChangeCategoryDialog() {
        screenModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            val categories = getNovelCategories.await().filter { it.id > 0L }
            if (categories.isEmpty()) return@launchIO
            val current = getNovelCategories.awaitByNovelId(novel.id).map { it.id }.toSet()
            updateLoaded { it.copy(dialog = NovelDetailsDialog.ChangeCategory(categories, current)) }
        }
    }

    fun applyCategories(categoryIds: List<Long>) {
        screenModelScope.launchIO {
            val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            setNovelCategories.await(novel.id, categoryIds)
            dismissDialog()
        }
    }

    // --- Edit info ---

    fun showEditNovelInfoDialog() {
        val n = (state.value as? NovelDetailsState.Loaded)?.displayNovel ?: return
        updateLoaded {
            it.copy(
                dialog = NovelDetailsDialog.EditInfo(
                    title = n.title,
                    author = n.author.orEmpty(),
                    artist = n.artist.orEmpty(),
                    description = n.description.orEmpty(),
                    genre = n.genre?.joinToString(", ").orEmpty(),
                    status = n.status,
                ),
            )
        }
    }

    /** Apply Edit-info. A field differing from the stored value sets its lock bit (so it survives a
     *  refresh); a status of UNKNOWN clears the lock. Title needs no lock (refresh never touches it). */
    fun updateNovelInfo(
        title: String,
        author: String,
        artist: String,
        description: String,
        genre: String,
        status: Long,
    ) {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            var flags = n.editedFlags
            flags = setEditedFlag(flags, NovelEditFlags.AUTHOR, author != n.author.orEmpty())
            flags = setEditedFlag(flags, NovelEditFlags.ARTIST, artist != n.artist.orEmpty())
            flags = setEditedFlag(flags, NovelEditFlags.DESCRIPTION, description != n.description.orEmpty())
            flags = setEditedFlag(flags, NovelEditFlags.GENRES, genre != n.genre?.joinToString(", ").orEmpty())
            flags =
                setEditedFlag(
                    flags,
                    NovelEditFlags.STATUS,
                    status != NovelStatusCode.UNKNOWN.toLong() && status != n.status,
                )
            novelRepo.update(
                n.copy(
                    title = title.ifBlank { n.title },
                    author = author.ifBlank { null },
                    artist = artist.ifBlank { null },
                    description = description.ifBlank { null },
                    genre = genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null },
                    status = if (status != NovelStatusCode.UNKNOWN.toLong()) status else n.status,
                    editedFlags = flags,
                ),
            )
            dismissDialog()
        }
    }

    /** Clear every override and re-fetch source values. */
    fun resetNovelInfo() {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            val cleared = n.copy(editedFlags = 0L)
            novelRepo.update(cleared)
            dismissDialog()
            source?.let { runCatching { fetchAndSync(it, cleared) } }
        }
    }

    // --- Chapter sort / filter / display ---

    fun setSortOrder(sort: Long, descending: Boolean) = updateChapterFlags { flags ->
        var f = setNovelFlag(flags, sort, NovelChapterFlags.SORTING_MASK)
        f =
            setNovelFlag(
                f,
                if (descending) NovelChapterFlags.SORT_DESC else NovelChapterFlags.SORT_ASC,
                NovelChapterFlags.SORT_DIR_MASK,
            )
        setNovelFlag(f, NovelChapterFlags.SORT_LOCAL, NovelChapterFlags.SORT_LOCAL_MASK)
    }

    fun setFilters(read: Long, bookmarked: Long) = updateChapterFlags { flags ->
        var f = setNovelFlag(flags, read, NovelChapterFlags.READ_MASK)
        f = setNovelFlag(f, bookmarked, NovelChapterFlags.BOOKMARKED_MASK)
        setNovelFlag(f, NovelChapterFlags.FILTER_LOCAL, NovelChapterFlags.FILTER_LOCAL_MASK)
    }

    fun setHideChapterTitles(hide: Boolean) = updateChapterFlags { flags ->
        val display = if (hide) NovelChapterFlags.DISPLAY_NUMBER else NovelChapterFlags.DISPLAY_NAME
        val f = setNovelFlag(flags, display, NovelChapterFlags.DISPLAY_MASK)
        setNovelFlag(f, NovelChapterFlags.SORT_LOCAL, NovelChapterFlags.SORT_LOCAL_MASK)
    }

    /** Write the current view as the global chapter-settings default and drop this novel's overrides. */
    fun setChapterSettingsAsDefault() {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            novelPreferences.defaultChapterSortOrder().set(loaded.sorting)
            novelPreferences.defaultChapterSortDescending().set(loaded.sortDescending)
            novelPreferences.defaultChapterHideTitles().set(loaded.hideChapterTitles)
            novelPreferences.defaultChapterFilterUnread().set(loaded.readFilter)
            novelPreferences.defaultChapterFilterBookmarked().set(loaded.bookmarkedFilter)
            novelRepo.update(loaded.novel.copy(chapterFlags = clearLocalBits(loaded.novel.chapterFlags)))
        }
    }

    /** Drop this novel's overrides so the global default applies. */
    fun resetChapterSettings() {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            novelRepo.update(n.copy(chapterFlags = clearLocalBits(n.chapterFlags)))
        }
    }

    private fun clearLocalBits(flags: Long): Long =
        setNovelFlag(
            setNovelFlag(flags, 0L, NovelChapterFlags.SORT_LOCAL_MASK),
            0L,
            NovelChapterFlags.FILTER_LOCAL_MASK,
        )

    private inline fun updateChapterFlags(crossinline transform: (Long) -> Long) {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            novelRepo.update(n.copy(chapterFlags = transform(n.chapterFlags)))
        }
    }

    fun showChapterSettingsDialog() = updateLoaded { it.copy(dialog = NovelDetailsDialog.ChapterSettings) }

    // --- Selection + read / bookmark ---

    fun toggleSelection(chapterId: Long, fromLongPress: Boolean) {
        updateLoaded { loaded ->
            val index = loaded.chapters.indexOfFirst { it.id == chapterId }
            if (index < 0) return@updateLoaded loaded
            val sel = loaded.selection.toMutableSet()
            if (fromLongPress && loaded.selection.isNotEmpty() && selectionAnchor[0] >= 0) {
                val from = minOf(selectionAnchor[0], index)
                val to = maxOf(selectionAnchor[0], index)
                for (i in from..to) sel.add(loaded.chapters[i].id)
                selectionAnchor[1] = index
            } else {
                if (chapterId in sel) sel.remove(chapterId) else sel.add(chapterId)
                selectionAnchor[0] = index
                selectionAnchor[1] = index
            }
            loaded.copy(selection = sel)
        }
    }

    fun selectAll() = updateLoaded { it.copy(selection = it.chapters.mapTo(HashSet()) { ch -> ch.id }) }

    fun invertSelection() = updateLoaded { loaded ->
        loaded.copy(selection = loaded.chapters.mapNotNull { it.id.takeIf { id -> id !in loaded.selection } }.toSet())
    }

    fun clearSelection() {
        selectionAnchor[0] = -1
        selectionAnchor[1] = -1
        updateLoaded { it.copy(selection = emptySet()) }
    }

    fun markSelectedRead(read: Boolean) = withSelection { chapters ->
        chapterRepo.setReadBulk(chapters.map { it.id }, read)
    }

    fun bookmarkSelected(bookmark: Boolean) = withSelection { chapters ->
        chapters.forEach { chapterRepo.setBookmark(it.id, bookmark) }
    }

    /** Mark every chapter before the earliest selected one (in source order) read/unread. Spans all
     *  fetched pages (operates on stored rows), not just the page on screen. */
    fun markPreviousRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val ascending = chapterRepo.getByNovelId(loaded.novel.id).sortedBy { it.sourceOrder }
            val earliest = ascending.indexOfFirst { it.id in loaded.selection }
            if (earliest > 0) chapterRepo.setReadBulk(ascending.subList(0, earliest).map { it.id }, read)
            clearSelection()
        }
    }

    fun markAllRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            chapterRepo.setReadBulk(chapterRepo.getByNovelId(loaded.novel.id).map { it.id }, read)
        }
    }

    fun toggleChapterBookmark(chapter: NovelChapter) {
        screenModelScope.launchIO { chapterRepo.setBookmark(chapter.id, !chapter.bookmark) }
    }

    fun markChapterRead(chapter: NovelChapter, read: Boolean) {
        screenModelScope.launchIO { chapterRepo.setReadBulk(listOf(chapter.id), read) }
    }

    private inline fun withSelection(crossinline block: suspend (List<NovelChapter>) -> Unit) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val chapters = loaded.chapters.filter { it.id in loaded.selection }
            if (chapters.isNotEmpty()) block(chapters)
            clearSelection()
        }
    }

    // --- Downloads ---

    fun onChapterDownloadAction(chapter: NovelChapter, action: ChapterDownloadAction) {
        when (action) {
            ChapterDownloadAction.START -> downloadManager.downloadChapters(listOf(chapter))
            ChapterDownloadAction.START_NOW -> {
                downloadManager.downloadChapters(listOf(chapter))
                downloadManager.startDownloadNow(chapter.id)
            }
            ChapterDownloadAction.CANCEL -> downloadManager.cancelDownloads(listOf(chapter.id))
            ChapterDownloadAction.DELETE -> downloadManager.deleteChapters(listOf(chapter))
        }
    }

    fun downloadSelected() = withSelection { downloadManager.downloadChapters(it) }

    fun deleteSelected() = withSelection { downloadManager.deleteChapters(it) }

    private fun NovelDownload.State.toDownloadState(): Download.State = when (this) {
        NovelDownload.State.QUEUE -> Download.State.QUEUE
        NovelDownload.State.DOWNLOADING -> Download.State.DOWNLOADING
        NovelDownload.State.ERROR -> Download.State.ERROR
    }

    fun dismissDialog() = updateLoaded { it.copy(dialog = null) }

    private inline fun updateLoaded(crossinline transform: (NovelDetailsState.Loaded) -> NovelDetailsState.Loaded) {
        mutableState.update { (it as? NovelDetailsState.Loaded)?.let(transform) ?: it }
    }
}

sealed interface NovelDetailsState {
    data object Loading : NovelDetailsState
    data class Failed(val message: String) : NovelDetailsState

    @Immutable
    data class Loaded(
        /** Identity + favorite key. */
        val novel: Novel,
        /** Metadata shown in the header. Equals [novel] for a single source; the S8 merge seam
         *  repoints it at the selected source. */
        val displayNovel: Novel,
        val chapters: List<NovelChapter>,
        /** Page keys for the selector; empty when the source is single/unpaged (selector hidden). */
        val pages: List<String> = emptyList(),
        val pageIndex: Int = 0,
        /** A lazy page fetch is in flight. */
        val isPageLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        /** Live download-queue states by chapter id (queued/downloading/error only; a finished
         *  download is signalled by the chapter row's `isDownloaded` flag). */
        val downloadStates: Map<Long, Download.State> = emptyMap(),
        val dialog: NovelDetailsDialog? = null,
        val selection: Set<Long> = emptySet(),
        val resumeChapter: NovelChapter? = null,
        val hasStarted: Boolean = false,
        /** Cover-derived header tint; null when off or not yet extracted. */
        val seedColor: Color? = null,
        /** Resolved source name + homepage (for the header line + WebView); fall back to the id/url. */
        val sourceName: String = "",
        val sourceUrl: String? = null,
        // Resolved (per-novel or global-default) chapter view settings.
        val sorting: Long = NovelChapterFlags.SORTING_SOURCE,
        val sortDescending: Boolean = true,
        val readFilter: Long = 0L,
        val bookmarkedFilter: Long = 0L,
        val hideChapterTitles: Boolean = false,
        /** Source-switcher chips for a merged group (empty/single = not merged, chips hidden). */
        val mergeSources: List<NovelMergeSourceInfo> = emptyList(),
        /** The selected source chip's novelId; null = the unified ("All") view. */
        val selectedSourceNovelId: Long? = null,
    ) : NovelDetailsState {
        val selectionMode: Boolean get() = selection.isNotEmpty()

        /** More than one page/volume to choose between, so the page selector is shown. */
        val isPaged: Boolean get() = pages.size > 1
    }
}

/** One source chip in a merged novel's source switcher. [isCurrent] marks the opened (anchor) source. */
@Immutable
data class NovelMergeSourceInfo(val novelId: Long, val sourceName: String, val isCurrent: Boolean)

sealed interface NovelDetailsDialog {
    data class ChangeCategory(
        val allCategories: List<NovelCategory>,
        val currentCategoryIds: Set<Long>,
    ) : NovelDetailsDialog

    data class EditInfo(
        val title: String,
        val author: String,
        val artist: String,
        val description: String,
        val genre: String,
        val status: Long,
    ) : NovelDetailsDialog

    data object ChapterSettings : NovelDetailsDialog
    data object PageSelector : NovelDetailsDialog
}
