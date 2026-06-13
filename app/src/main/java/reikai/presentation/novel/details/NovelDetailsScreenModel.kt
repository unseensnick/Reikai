package reikai.presentation.novel.details

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import reikai.data.novel.NovelStatusCode
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.data.novel.toNovel
import reikai.domain.novel.NovelChapterRepository
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
    private val sourceManager: NovelSourceManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelCategories: SetNovelCategories by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    private val uiPreferences: UiPreferences by injectLazy()

    /** Resolved once the plugin host loads it; source-dependent ops defer until set. */
    @Volatile
    private var source: NovelSource? = null

    /** A first-open fetch (no stored chapters) runs at most once. */
    private var firstFetchTried = false
    private var refreshJob: Job? = null
    private var seedExtracted = false

    /** Range-select anchor into the displayed chapter order; -1 when no selection. */
    private val selectionAnchor = intArrayOf(-1, -1)

    init {
        observeFromDb()
        resolveSource()
    }

    private fun resolveSource() {
        screenModelScope.launchIO {
            try { installer.ensureLoaded() } catch (_: Throwable) {}
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

    // DB-first: the stored novel drives an inner chapter flow; every emission rebuilds Loaded from
    // stored rows. A change to the novel's chapterFlags re-emits the outer flow, re-sorting the list.
    private fun observeFromDb() {
        screenModelScope.launchIO {
            novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId).collectLatest { novel ->
                if (novel == null) {
                    maybeFirstFetch(null)
                    return@collectLatest
                }
                chapterRepo.getByNovelIdAsFlow(novel.id).collectLatest { chapters ->
                    rebuildLoaded(novel, chapters)
                    if (chapters.isEmpty()) maybeFirstFetch(novel)
                }
            }
        }
    }

    private fun rebuildLoaded(novel: Novel, chapters: List<NovelChapter>) {
        val display = chapters.sortedAndFiltered(novel, novelPreferences)
        val resume = chapters.sortedBy { it.sourceOrder }.firstOrNull { !it.read }
        mutableState.update { prev ->
            val loaded = prev as? NovelDetailsState.Loaded
            NovelDetailsState.Loaded(
                novel = novel,
                displayNovel = novel,
                chapters = display,
                isRefreshing = loaded?.isRefreshing ?: false,
                dialog = loaded?.dialog,
                selection = loaded?.selection.orEmpty().filterTo(HashSet()) { id -> chapters.any { it.id == id } },
                resumeChapter = resume,
                hasStarted = chapters.any { it.read },
                seedColor = loaded?.seedColor,
                sourceName = source?.name ?: loaded?.sourceName ?: sourceId,
                sourceUrl = source?.site ?: loaded?.sourceUrl,
                sorting = novel.effectiveSorting(novelPreferences),
                sortDescending = novel.effectiveSortDescending(novelPreferences),
                readFilter = novel.effectiveReadFilter(novelPreferences),
                bookmarkedFilter = novel.effectiveBookmarkedFilter(novelPreferences),
                hideChapterTitles = novel.effectiveHideChapterTitles(novelPreferences),
            )
        }
        updateSeedColor(novel)
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

    /** parseNovel + persist metadata (edit-lock + blank safe) + sync chapters. The reactive flow then
     *  re-emits the updated novel/chapter list. A novel opened from Browse is inserted non-favorite. */
    private suspend fun fetchAndSync(src: NovelSource, existing: Novel?) {
        val sourceNovel = src.parseNovel(existing?.url ?: novelUrl)
        val target = if (existing != null) {
            val parsed = sourceNovel.toNovel(sourceId = src.id, favorite = existing.favorite)
            val merged = mergeRefreshedNovel(existing, parsed)
            if (merged != existing) novelRepo.update(merged)
            merged
        } else {
            // Non-favorite shadow row so a browse-opened novel is viewable without being silently
            // added; insertOrGet reuses a concurrently-created row instead of duplicating.
            novelRepo.insertOrGet(sourceNovel.toNovel(sourceId = src.id, favorite = false)) ?: return
        }
        val chapters = sourceNovel.chapters.orEmpty()
        if (chapters.isNotEmpty()) {
            syncChaptersWithNovelSource(chapters, target, chapterRepo, novelRepo, database)
        }
    }

    /** Stale-then-fresh: the cached list stays under a spinner while the sync runs, then the flow
     *  swaps in fresh rows. Read/bookmark are preserved by the sync. Deduped against concurrent runs. */
    fun refresh() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val src = source ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = screenModelScope.launchIO {
            mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = true) ?: it }
            try {
                runCatching { fetchAndSync(src, loaded.novel) }
            } finally {
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = false) ?: it }
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
    fun updateNovelInfo(title: String, author: String, artist: String, description: String, genre: String, status: Long) {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            var flags = n.editedFlags
            flags = setEditedFlag(flags, NovelEditFlags.AUTHOR, author != n.author.orEmpty())
            flags = setEditedFlag(flags, NovelEditFlags.ARTIST, artist != n.artist.orEmpty())
            flags = setEditedFlag(flags, NovelEditFlags.DESCRIPTION, description != n.description.orEmpty())
            flags = setEditedFlag(flags, NovelEditFlags.GENRES, genre != n.genre?.joinToString(", ").orEmpty())
            flags = setEditedFlag(flags, NovelEditFlags.STATUS, status != NovelStatusCode.UNKNOWN.toLong() && status != n.status)
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
        f = setNovelFlag(f, if (descending) NovelChapterFlags.SORT_DESC else NovelChapterFlags.SORT_ASC, NovelChapterFlags.SORT_DIR_MASK)
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
        setNovelFlag(setNovelFlag(flags, 0L, NovelChapterFlags.SORT_LOCAL_MASK), 0L, NovelChapterFlags.FILTER_LOCAL_MASK)

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

    /** Mark every chapter before the earliest selected one (in source order) read/unread. */
    fun markPreviousRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val ascending = loaded.chapters.sortedBy { it.sourceOrder }
            val earliest = ascending.indexOfFirst { it.id in loaded.selection }
            if (earliest > 0) chapterRepo.setReadBulk(ascending.subList(0, earliest).map { it.id }, read)
            clearSelection()
        }
    }

    fun markAllRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            chapterRepo.setReadBulk(loaded.chapters.map { it.id }, read)
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
        val isRefreshing: Boolean = false,
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
    ) : NovelDetailsState {
        val selectionMode: Boolean get() = selection.isNotEmpty()
    }
}

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
}
