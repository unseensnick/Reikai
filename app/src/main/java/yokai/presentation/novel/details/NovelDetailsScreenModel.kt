package yokai.presentation.novel.details

import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toFile
import androidx.palette.graphics.Palette
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import java.net.URL
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithNovelSource
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy
import yokai.data.DatabaseHandler
import yokai.data.novel.NovelStatusCode
import yokai.data.novel.toNovel
import yokai.domain.novel.NovelChapterAggregation
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelMergeManager
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.NovelRepository
import yokai.domain.storage.StorageManager
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.domain.novel.interactor.SetNovelCategories
import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelChapter
import yokai.domain.novel.models.effectiveBookmarkedFilter
import yokai.domain.novel.models.effectiveHideChapterTitles
import yokai.domain.novel.models.effectiveReadFilter
import yokai.domain.novel.models.effectiveSortDescending
import yokai.domain.novel.models.effectiveSorting
import yokai.domain.novel.models.setFlag
import yokai.domain.novel.models.sortedAndFiltered
import yokai.domain.ui.UiPreferences
import yokai.novel.download.NovelDownload
import yokai.novel.download.NovelDownloadManager
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager
import yokai.novel.text.htmlToParagraphs
import yokai.presentation.details.ChapterDownloadAction
import yokai.presentation.details.DetailsDownloadState
import yokai.presentation.details.DetailsEvent
import yokai.presentation.details.ManageSourceItem
import yokai.presentation.novel.reader.ChapterRead

/** Max chapters held in the reader's session text cache (LRU). Small: covers the current chapter,
 *  a prefetched next, and a little back-flip history without holding much text in memory. */
private const val MAX_CACHED_CHAPTERS = 5

/**
 * Database-first details for a SAVED (library) novel (Phase 7). The chapter list comes from the DB
 * via a reactive Flow, so a saved novel opens offline and shows read/bookmark from stored rows; the
 * source plugin is hit only on the first-ever open (nothing stored) or an explicit [refresh]. New
 * chapters arrive in the background via `NovelUpdateJob` and surface through the Flow, mirroring the
 * manga side (details never fetch on open).
 *
 * The plugin host needs an Activity `Context` and the ScreenModel outlives configuration changes, so
 * source resolution + the host live in the composable; it hands the resolved [NovelSource] here via
 * [onSourceReady]. The DB-first read needs no source; only first-open / refresh / chapter-text do.
 */
class NovelDetailsScreenModel(
    private val sourceId: String,
    private val novelUrl: String,
) : StateScreenModel<NovelDetailsState>(NovelDetailsState.Loading) {

    private val novelRepo: NovelRepository by injectLazy()
    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val handler: DatabaseHandler by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelCategories: SetNovelCategories by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()
    private val uiPreferences: UiPreferences by injectLazy()
    private val storageManager: StorageManager by injectLazy()

    // The novel source registry, populated by the screen's plugin-host load. Used to resolve a
    // grouped sibling's source for the chip label and for opening a gap-filled chapter (which must
    // read from its own source). Singleton, so it sees every source the screen has loaded.
    private val sourceManager: NovelSourceManager by injectLazy()
    private val downloadManager: NovelDownloadManager by injectLazy()
    private val mergeManager = NovelMergeManager()

    /** One-shot screen effects (undo snackbars, sibling navigation, cover share). Buffered so emits
     *  never suspend; collected by [yokai.presentation.details.HandleDetailsEvents] in the screen. */
    private val _events = MutableSharedFlow<DetailsEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DetailsEvent> = _events.asSharedFlow()
    fun emitEvent(event: DetailsEvent) { _events.tryEmit(event) }

    /** Resolve a grouped sibling's (sourceId, url) route for post-split navigation (the novel screen
     *  is keyed by source+url, not the Long id [DetailsEvent.NavigateToSibling] carries). */
    fun siblingRoute(novelId: Long): Pair<String, String>? = novelsById[novelId]?.let { it.source to it.url }

    // Merge state. groupIdsFlow drives the chapter source set (just the anchor, or every grouped
    // sibling once resolved); sourceViewFlow picks the unified list (null) or one source's view.
    private val sourceViewFlow = MutableStateFlow<Long?>(null)
    private val groupIdsFlow = MutableStateFlow<List<Long>>(emptyList())

    // Hidden chapters (pref-backed, keyed "novelId|url"). showHiddenFlow toggles whether they're
    // revealed (dimmed) for unhiding. Both fold into the pipeline so a change rebuilds the list.
    private val hiddenChaptersPref = novelPreferences.hiddenChapters()
    private val showHiddenFlow = MutableStateFlow(false)

    /** Gates the one-shot group resolution (mirrors the manga side's dedup window). */
    @Volatile
    private var relatedFetched = false

    /** Snapshot of the grouped novels (id -> Novel) for source labels + per-sibling source ids in
     *  aggregation. Empty for a single-source novel. */
    private var novelsById: Map<Long, Novel> = emptyMap()

    /** Latest per-novel chapter lists (pristine, native source_order) for the per-source view. */
    private var chaptersByNovel: Map<Long, List<NovelChapter>> = emptyMap()

    /** Resolved by the screen once the plugin host loads it; null until then. Source-dependent ops
     *  (first-open fetch, refresh, chapter text) no-op or defer until it's set. */
    @Volatile
    private var source: NovelSource? = null

    /** A library novel already has its chapters synced at favorite time, so the first-open fetch is
     *  a rare fallback; guard it so it runs at most once. */
    private var firstFetchTried = false
    private var refreshJob: Job? = null

    /** Range-select anchors [min, max] into the displayed chapter order; -1 when no selection.
     *  Mirrors the manga side so a long-press extends the range. */
    private val selectedPositions = intArrayOf(-1, -1)

    /** Session-scoped LRU of raw chapter HTML keyed by chapter id (RAM-only, dies with the screen).
     *  Makes re-opening and prefetched next chapters instant. Raw HTML (not parsed paragraphs) so the
     *  WebView reader can render markup; the plain-text reader parses it on serve. Synchronized because
     *  the prefetch coroutine and the reader both touch it. */
    private val chapterTextCache: MutableMap<Long, String> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Long, String>(MAX_CACHED_CHAPTERS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>) =
                size > MAX_CACHED_CHAPTERS
        },
    )

    init {
        observeFromDb()
        observeDownloads()
    }

    /** Mirror the download engine's queue into the Loaded state so the per-chapter chip reflects
     *  QUEUE/DOWNLOADING/ERROR. Completed downloads leave the queue; the chapter's `isDownloaded`
     *  flag (carried by the DB flow) is what then shows the DOWNLOADED check. */
    private fun observeDownloads() {
        screenModelScope.launchIO {
            downloadManager.queueState.collectLatest { queue ->
                val byChapter = queue.associate { it.chapterId to it.state.toDetailsDownloadState() }
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(downloads = byChapter) ?: it }
            }
        }
    }

    private fun NovelDownload.State.toDetailsDownloadState(): DetailsDownloadState = when (this) {
        // Text has no byte progress, so an active download shows the same indeterminate spinner as a
        // queued one rather than an empty progress ring.
        NovelDownload.State.QUEUE, NovelDownload.State.DOWNLOADING -> DetailsDownloadState.QUEUED
        NovelDownload.State.ERROR -> DetailsDownloadState.ERROR
    }

    /** Act on a chapter's download indicator: queue (or retry an error), jump the queue, or
     *  cancel/delete. The menu surfaces Start-now / Cancel while queued and Delete while downloaded; a
     *  plain tap on a not-downloaded (or errored) chapter starts it. Mirrors the manga side. */
    fun downloadAction(chapterId: Long, action: ChapterDownloadAction) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val chapter = loaded.chapters.find { it.id == chapterId } ?: return
        when (action) {
            ChapterDownloadAction.START -> downloadManager.downloadChapters(listOf(chapter))
            ChapterDownloadAction.START_NOW -> downloadManager.startDownloadNow(chapterId)
            // deleteChapters removes a queued entry and deletes a downloaded file in one call, covering
            // both Cancel (queued) and Delete (downloaded).
            ChapterDownloadAction.CANCEL, ChapterDownloadAction.DELETE -> downloadManager.deleteChapters(listOf(chapter))
        }
    }

    // DB-first: the anchor-novel Flow drives an inner chapter Flow; every emission rebuilds Loaded
    // from stored rows. No source call on this path. For a merged novel the inner flow combines all
    // grouped siblings' chapters (resolved once via the merge prefs); groupIdsFlow re-subscribes it.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFromDb() {
        screenModelScope.launchIO {
            novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId).collectLatest { novel ->
                if (novel == null) {
                    // Not in the DB (unexpected for a library entry); needs a first-open fetch once
                    // the source resolves. Keep any already-loaded state visible.
                    maybeFirstFetch(null)
                    return@collectLatest
                }
                val anchorId = novel.id
                if (anchorId == null) {
                    maybeFirstFetch(novel)
                    return@collectLatest
                }
                maybeLoadGroup(anchorId, novel.title)

                groupIdsFlow
                    .flatMapLatest { group ->
                        val ids = if (group.size > 1) group else listOf(anchorId)
                        combine(ids.map { id -> chapterRepo.getByNovelIdAsFlow(id).map { id to it } }) { it.toMap() }
                    }
                    // Re-render when the chosen source view, the hidden set, or the show-hidden
                    // toggle changes, without re-fetching chapters.
                    .combine(sourceViewFlow) { byNovel, _ -> byNovel }
                    .combine(hiddenChaptersPref.changes()) { byNovel, _ -> byNovel }
                    .combine(showHiddenFlow) { byNovel, _ -> byNovel }
                    .collectLatest { byNovel ->
                        chaptersByNovel = byNovel
                        novelLog {
                            "served from DB: \"${novel.title}\" group=${groupIdsFlow.value.size} " +
                                "chapters=${byNovel.values.sumOf { it.size }} (no source hit)"
                        }
                        rebuildLoaded(novel, byNovel)
                        if (byNovel[anchorId].isNullOrEmpty()) maybeFirstFetch(novel)
                    }
            }
        }
    }

    /** Resolve the merge group once, then publish it so the chapter pipeline re-aggregates. Launched
     *  (not awaited) so the single-source list shows immediately and expands to unified when ready. */
    private fun maybeLoadGroup(anchorId: Long, title: String) {
        if (relatedFetched) return
        relatedFetched = true
        screenModelScope.launchIO {
            val ids = mergeManager.computeRelatedNovelIds(anchorId, title)
            updateGroup(if (ids.size > 1) ids.toList() else listOf(anchorId))
        }
    }

    /** Apply a new merge-group membership: snapshot the sibling novels (for chips + aggregation
     *  source ids), reset the view to unified, then publish the ids so the pipeline re-aggregates. */
    private suspend fun updateGroup(ids: List<Long>) {
        novelsById = if (ids.size > 1) {
            ids.mapNotNull { id -> novelRepo.getById(id) }.mapNotNull { n -> n.id?.let { it to n } }.toMap()
        } else {
            emptyMap()
        }
        sourceViewFlow.value = null
        groupIdsFlow.value = ids
    }

    /** Switch the chapter list between the unified view (null) and a single grouped source. Clears
     *  any active selection since the chapter set changes. */
    fun setSourceView(novelId: Long?) {
        if (sourceViewFlow.value == novelId) return
        clearSelection()
        sourceViewFlow.value = novelId
    }

    // --- Source grouping (split / remove from library) ---

    /** Resolve the grouped sources (favorited, not unmerged) and open the Manage sources dialog. */
    fun showManageSourcesDialog() {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val anchorId = loaded.novel.id ?: return@launchIO
            val items = mergeManager.availableSources(anchorId, groupIdsFlow.value.toLongArray())
                .mapNotNull { n -> n.id?.let { ManageSourceItem(mangaId = it, sourceName = sourceLabel(n), isCurrent = it == anchorId) } }
            mutableState.update {
                (it as? NovelDetailsState.Loaded)?.copy(dialog = NovelDetailsDialog.ManageSources(items)) ?: it
            }
        }
    }

    /**
     * Split [targetIds] out of the merge group (they stay in the library, just ungrouped). The commit
     * is deferred behind an Undo snackbar: tapping Undo cancels it so nothing is persisted. If the
     * currently-viewed source is split out, redirect to a remaining sibling so the user stays on the
     * group. Mirrors the manga side.
     */
    fun splitSources(targetIds: List<Long>) {
        if (targetIds.isEmpty()) { dismissDialog(); return }
        dismissDialog()
        val anchorId = (state.value as? NovelDetailsState.Loaded)?.novel?.id
        val sibling = if (anchorId != null && anchorId in targetIds) {
            groupIdsFlow.value.firstOrNull { it != anchorId && it !in targetIds }
        } else {
            null
        }
        val currentGroup = groupIdsFlow.value
        emitEvent(
            DetailsEvent.Snackbar(
                message = if (targetIds.size == 1) "Source split from group" else "${targetIds.size} sources split from group",
                actionLabel = "Undo",
                onDismiss = {
                    screenModelScope.launchIO {
                        val newIds = mergeManager.removeFromGroup(currentGroup.toLongArray(), targetIds)
                        novelLog { "splitSources removed=${targetIds.size} remaining=${newIds.size}" }
                        if (sibling != null) emitEvent(DetailsEvent.NavigateToSibling(sibling))
                        else updateGroup(newIds.toList())
                    }
                },
            ),
        )
    }

    /**
     * Remove [targetIds] from the library (unfavorite) and drop them from the group. Deferred behind an
     * Undo snackbar; tapping Undo cancels it so nothing is unfavorited. No tracker / download / cover
     * cleanup (none exists for novels). Mirrors the manga side.
     */
    fun removeSourcesFromLibrary(targetIds: List<Long>) {
        if (targetIds.isEmpty()) { dismissDialog(); return }
        dismissDialog()
        val currentGroup = groupIdsFlow.value
        emitEvent(
            DetailsEvent.Snackbar(
                message = if (targetIds.size == 1) "Source removed from library" else "${targetIds.size} sources removed from library",
                actionLabel = "Undo",
                onDismiss = {
                    screenModelScope.launchIO {
                        mergeManager.unfavoriteFromGroup(targetIds)
                        novelLog { "removeSourcesFromLibrary count=${targetIds.size}" }
                        val targetSet = targetIds.toSet()
                        updateGroup(currentGroup.filterNot { it in targetSet })
                    }
                },
            ),
        )
    }

    /** Remove every grouped source from the library at once (favorite long-press on a merged title). */
    fun removeAllSourcesFromLibrary() = removeSourcesFromLibrary(groupIdsFlow.value)

    /** Build [NovelDetailsState.Loaded] from the per-novel chapter map, preserving transient UI. */
    private fun rebuildLoaded(novel: Novel, byNovel: Map<Long, List<NovelChapter>>) {
        val anchorId = novel.id ?: return
        val merged = groupIdsFlow.value.size > 1
        val sourceView = sourceViewFlow.value
        // Hidden chapters are filtered out of the list (and the merge) unless the user is showing
        // them to unhide. hasHiddenChapters gates the overflow toggle regardless of that view.
        val hidden = hiddenChaptersPref.get()
        val showHidden = showHiddenFlow.value
        val hasHiddenChapters = hidden.isNotEmpty() && byNovel.values.any { chs -> chs.any { hiddenKey(it) in hidden } }
        val effectiveByNovel = if (showHidden || hidden.isEmpty()) {
            byNovel
        } else {
            byNovel.mapValues { (_, chs) -> chs.filterNot { hiddenKey(it) in hidden } }
        }
        val unified = if (merged) {
            stampMergedReadingOrder(
                NovelChapterAggregation.aggregate(effectiveByNovel, sourceIdsForAggregation(), preferredSourceIds()),
            )
        } else {
            effectiveByNovel[anchorId].orEmpty()
        }
        // The per-source chip view shows that one source's own (pristine) chapters; otherwise the
        // unified stitched list. Fall back to unified if a stale source id no longer resolves.
        val rawChapters = if (sourceView != null && effectiveByNovel.size > 1) effectiveByNovel[sourceView] ?: unified else unified
        // When revealing hidden chapters, mark which displayed ids are hidden so the row dims.
        val hiddenChapterIds = if (showHidden) rawChapters.filter { hiddenKey(it) in hidden }.mapNotNull { it.id }.toSet() else emptySet()
        // Resume is computed from the displayed set in reading order, independent of sort/filter, so
        // hiding read chapters doesn't break the FAB.
        val byOrder = rawChapters.sortedBy { it.sourceOrder }
        val tabs = if (merged) {
            groupIdsFlow.value.mapNotNull { id -> novelsById[id]?.let { SourceTab(id, sourceLabel(it)) } }
        } else {
            emptyList()
        }
        // Header source line: "Unified" for the pooled view of a merged novel, otherwise the viewed
        // source's name (the selected chip, or the anchor for a single-source novel).
        val displayNovel = sourceView?.let { novelsById[it] } ?: novel
        val label = if (sourceView == null && merged) "Unified" else sourceLabel(displayNovel)
        mutableState.update { current ->
            NovelDetailsState.Loaded(
                novel = novel,
                displayNovel = displayNovel,
                chapters = rawChapters.sortedAndFiltered(novel, novelPreferences),
                isRefreshing = (current as? NovelDetailsState.Loaded)?.isRefreshing ?: false,
                // Preserve transient screen state across DB re-emissions (a read/bookmark write
                // re-emits the chapter list): an open dialog and the active selection.
                dialog = (current as? NovelDetailsState.Loaded)?.dialog,
                selection = (current as? NovelDetailsState.Loaded)?.selection ?: emptySet(),
                resumeChapter = byOrder.firstOrNull { !it.read } ?: byOrder.lastOrNull(),
                hasStarted = rawChapters.any { it.read || it.lastTextProgress > 0 },
                accentColor = (current as? NovelDetailsState.Loaded)?.accentColor,
                // Survive DB re-emissions (a read/bookmark/download-flag write re-emits the list).
                downloads = (current as? NovelDetailsState.Loaded)?.downloads ?: emptyMap(),
                sorting = novel.effectiveSorting(novelPreferences),
                sortDescending = novel.effectiveSortDescending(novelPreferences),
                readFilter = novel.effectiveReadFilter(novelPreferences),
                bookmarkedFilter = novel.effectiveBookmarkedFilter(novelPreferences),
                hideChapterTitles = novel.effectiveHideChapterTitles(novelPreferences),
                sourceTabs = tabs,
                sourceView = sourceView,
                sourceLabel = label,
                displayStatus = displayNovel.status,
                showHidden = showHidden,
                hiddenChapterIds = hiddenChapterIds,
                hasHiddenChapters = hasHiddenChapters,
            )
        }
    }

    private var accentColorLoaded = false

    /**
     * Extract a vibrant accent from the cover (once, gated on the shared themeMangaDetails pref) so the
     * novel header backdrop tints per-cover, matching the manga details screen. The cover is a remote
     * URL, so load it through Coil rather than the manga cover cache.
     */
    fun loadAccentColor() {
        if (accentColorLoaded || !preferences.themeMangaDetails().get()) return
        if ((state.value as? NovelDetailsState.Loaded)?.novel?.thumbnailUrl.isNullOrBlank()) return
        accentColorLoaded = true
        screenModelScope.launchIO {
            val bitmap = loadCoverBitmap() ?: return@launchIO
            val color = Palette.from(bitmap).generate().getBestColor() ?: return@launchIO
            mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(accentColor = color) ?: it }
        }
    }

    /** Load the cover into a software bitmap via Coil (novels have no on-disk CoverCache), else null. */
    private suspend fun loadCoverBitmap(): Bitmap? {
        val url = (state.value as? NovelDetailsState.Loaded)?.displayNovel?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return null
        val context = preferences.context
        val request = ImageRequest.Builder(context).data(url).build()
        val drawable = context.imageLoader.execute(request).image?.asDrawable(context.resources) ?: return null
        // toBitmap may hand back a HARDWARE-config bitmap; Palette / compress can't read those.
        return drawable.toBitmap().let {
            if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
        }
    }

    /** Save the cover to the device's Covers directory; result reported via a snackbar event. */
    fun saveCover() {
        screenModelScope.launchIO {
            val ok = runCatching {
                val novel = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@runCatching false
                val bitmap = loadCoverBitmap() ?: return@runCatching false
                val dir = storageManager.getCoversDirectory() ?: return@runCatching false
                val file = dir.createFile(DiskUtil.buildValidFilename("${novel.title}.jpg")) ?: return@runCatching false
                file.openOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                DiskUtil.scanMedia(preferences.context, file)
                true
            }.getOrDefault(false)
            emitEvent(DetailsEvent.Snackbar(if (ok) "Cover saved" else "Couldn't save cover"))
        }
    }

    /** Copy the cover into a cache dir and hand the file to the screen to launch a share chooser. */
    fun shareCover() {
        screenModelScope.launchIO {
            val file = runCatching {
                val bitmap = loadCoverBitmap() ?: return@runCatching null
                val destDir = UniFile.fromFile(preferences.context.cacheDir)!!.createDirectory("shared_image")!!
                val out = destDir.createFile("cover.jpg")!!
                out.openOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                out.uri.toFile()
            }.getOrNull()
            if (file != null) emitEvent(DetailsEvent.ShareImage(file)) else emitEvent(DetailsEvent.Snackbar("Couldn't share cover"))
        }
    }

    /** Sibling novel id -> its source id, so [NovelChapterAggregation] can apply the preferred-source
     *  trunk pick. A sibling not yet snapshotted degrades to unranked, never to a wrong trunk. */
    private fun sourceIdsForAggregation(): Map<Long, String> = novelsById.mapValues { it.value.source }

    /** The global preferred-source ranking (slash-joined ids, highest priority first). Read at
     *  aggregate time; a setting change applies on the next open, not to an open screen. */
    private fun preferredSourceIds(): List<String> =
        novelPreferences.novelPreferredSources().get().split('/').filter { it.isNotBlank() }

    /** A grouped source's display name, falling back to its id when the plugin isn't loaded. */
    private fun sourceLabel(novel: Novel): String = sourceManager.get(novel.source)?.name ?: novel.source

    /**
     * Normalize a merged list's reading order. `source_order` is per-source, so the default
     * "by source order" sort interleaves sources nonsensically. Restamp it to follow chapter number
     * ascending (the novel reading order is chapter-1-first, unlike the manga side's newest-first),
     * so the existing sort / resume logic reads the unified list as one coherent series. The copies
     * keep the shared per-source lists in [chaptersByNovel] pristine for the per-source view.
     */
    private fun stampMergedReadingOrder(chapters: List<NovelChapter>): List<NovelChapter> =
        chapters.sortedWith(compareBy { it.chapterNumber })
            .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) }

    /** The source a chapter belongs to, so the reader opens against the source that has it. A merged
     *  list mixes sources; a gap-filled chapter resolves to its sibling's source. */
    private fun sourceForChapter(chapter: NovelChapter): NovelSource? {
        val owningSourceId = novelsById[chapter.novelId]?.source ?: sourceId
        return sourceManager.get(owningSourceId) ?: source
    }

    /** Called by the screen after it resolves the plugin source (host construction needs a Context).
     *  Kicks off a pending first-open fetch if the stored chapter list is empty. */
    fun onSourceReady(resolved: NovelSource) {
        source = resolved
        val loaded = state.value as? NovelDetailsState.Loaded
        // The same host load registered every installed sibling source too, so refresh any chip
        // labels that fell back to a raw source id (guarded so we never wipe a not-yet-loaded list).
        if (loaded != null && chaptersByNovel.isNotEmpty()) rebuildLoaded(loaded.novel, chaptersByNovel)
        if (loaded == null || loaded.chapters.isEmpty()) maybeFirstFetch(loaded?.novel)
    }

    /** The source couldn't be resolved (uninstalled, etc.). Only fatal when there's no DB data to
     *  show; otherwise the cached list stays and refresh is simply unavailable. */
    fun onSourceFailed(message: String) {
        if (state.value !is NovelDetailsState.Loaded) {
            mutableState.value = NovelDetailsState.Failed(message)
        }
    }

    /** Force-fetch this one novel from the source (stale-then-fresh): the cached list stays visible
     *  under a spinner while the sync runs, then the Flow swaps in the fresh rows. Read/bookmark are
     *  preserved by [syncChaptersWithNovelSource]. Deduped against a concurrent refresh. */
    fun refresh() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        if (refreshJob?.isActive == true) return
        novelLog { "refresh: fetching from source(s)" }
        refreshJob = screenModelScope.launchIO {
            mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = true) ?: it }
            try {
                val added = mutableListOf<NovelChapter>()
                val removed = mutableListOf<NovelChapter>()
                val group = groupIdsFlow.value
                if (group.size > 1) {
                    // Merged: refresh every grouped source so the unified list rebuilds with all of
                    // them. Per-source failures are logged and skipped. The anchor also refreshes its
                    // metadata (the displayed header); siblings sync chapters only.
                    group.forEach { id ->
                        runCatching {
                            val n = novelsById[id] ?: novelRepo.getById(id) ?: return@forEach
                            val (a, r) = if (id == loaded.novel.id) {
                                source?.let { fetchAndSync(it, n) } ?: return@forEach
                            } else {
                                sourceManager.get(n.source)?.let { fetchSiblingChapters(it, n) } ?: return@forEach
                            }
                            added += a
                            removed += r
                        }.onFailure { novelLog { "refresh source for novel $id failed: ${it.message}" } }
                    }
                } else {
                    val src = source ?: return@launchIO
                    runCatching {
                        val (a, r) = fetchAndSync(src, loaded.novel)
                        added += a
                        removed += r
                    }
                }
                handleSyncedChapters(added, removed)
            } finally {
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = false) ?: it }
            }
        }
    }

    /** Refresh one grouped source's chapters from its own url. No metadata merge (that's the anchor's
     *  job for the displayed header); the reactive flow re-emits and the unified list rebuilds. Returns
     *  the (added, removed) chapter diff so the caller can auto-download / prompt-on-removal. */
    private suspend fun fetchSiblingChapters(src: NovelSource, novel: Novel): Pair<List<NovelChapter>, List<NovelChapter>> {
        val chapters = src.parseNovel(novel.url).chapters.orEmpty()
        return if (chapters.isNotEmpty()) syncChaptersWithNovelSource(chapters, novel, src, chapterRepo, novelRepo, handler)
        else emptyList<NovelChapter>() to emptyList()
    }

    /**
     * React to a refresh sync: auto-download newly [added] chapters when the novel download-new pref
     * is on, and handle downloads orphaned by [removed] chapters per
     * [NovelPreferences.deleteRemovedChapters] (ask / keep / always). Mirrors the manga side's
     * handleSyncedChapters. The download-manager calls are fire-and-forget (they launch internally),
     * and the reactive flow re-emits the cleared download flags, so no manual row-state push.
     */
    private fun handleSyncedChapters(added: List<NovelChapter>, removed: List<NovelChapter>) {
        if (added.isNotEmpty() && novelPreferences.downloadNewChapters().get()) {
            downloadManager.downloadChapters(added.sortedBy { it.chapterNumber })
        }
        if (removed.isEmpty()) return
        val orphaned = removed.filter { downloadManager.isChapterDownloaded(it) }
        if (orphaned.isEmpty()) return
        when (novelPreferences.deleteRemovedChapters().get()) {
            2 -> downloadManager.deleteChapters(orphaned) // always delete
            1 -> Unit // always keep
            else -> { // ask
                val loaded = state.value as? NovelDetailsState.Loaded ?: return
                mutableState.value = loaded.copy(dialog = NovelDetailsDialog.ConfirmRemovedDownloads(orphaned))
            }
        }
    }

    /** Confirm handler for [NovelDetailsDialog.ConfirmRemovedDownloads]: delete the orphaned downloads. */
    fun deleteRemovedDownloads(chapters: List<NovelChapter>) {
        downloadManager.deleteChapters(chapters)
        dismissDialog()
    }

    /**
     * Fetch the chapter text for a stored chapter. Precedence: session cache -> on-disk download
     * (no host hit) -> live source. After serving, warms the next chapter into the cache so flipping
     * forward is instant (LNReader's reader feel). The cache is RAM-only and dies with the screen.
     */
    internal suspend fun loadChapterText(chapter: NovelChapter): ChapterRead {
        val chapterId = chapter.id ?: error("Stored chapter has no id")
        val html = chapterTextCache[chapterId]
            ?: loadChapterHtml(chapter).also { chapterTextCache[chapterId] = it }
        prefetchNextChapter(chapter)
        return ChapterRead(
            chapterId = chapterId,
            initialProgress = chapter.lastTextProgress,
            rawHtml = html,
            paragraphs = htmlToParagraphs(html),
        )
    }

    /** Downloaded chapter -> read from disk; otherwise a source hit routed through the chapter's own
     *  source so a gap-filled sibling reads correctly. Returns the raw HTML the source emitted. */
    private suspend fun loadChapterHtml(chapter: NovelChapter): String =
        downloadManager.getChapterText(chapter)
            ?: (sourceForChapter(chapter) ?: error("Source not ready")).parseChapter(chapter.url)

    /** Warm the next chapter (in reading order) into the cache off-thread. One speculative request
     *  per chapter-open at most, and none when the next chapter is already cached or downloaded, so
     *  it stays gentle on the source. */
    private fun prefetchNextChapter(current: NovelChapter) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val ordered = loaded.chapters.sortedBy { it.sourceOrder }
        val next = ordered.getOrNull(ordered.indexOfFirst { it.id == current.id } + 1) ?: return
        val nextId = next.id ?: return
        if (chapterTextCache.containsKey(nextId)) return
        screenModelScope.launchIO {
            runCatching { chapterTextCache[nextId] = loadChapterHtml(next) }
        }
    }

    /** Absolute web URL for this novel. Prefer the plugin's `resolveUrl` (handles sources whose URL
     *  isn't a plain site+path); otherwise resolve the stored novel path against the site the same way
     *  a default resolveUrl would (`new URL(path, site)`), which reaches the novel page for nearly all
     *  sources since the path is the one parseNovel already used. Homepage only if that can't be built. */
    suspend fun novelWebUrl(): String? {
        val src = source ?: return null
        src.resolveUrl(novelUrl, isNovel = true)?.takeIf { it.isNotBlank() }?.let { return it }
        val site = src.site.takeIf { it.isNotBlank() } ?: return null
        if (novelUrl.isBlank()) return site
        return runCatching { URL(URL(site), novelUrl).toString() }.getOrNull() ?: site
    }

    private fun maybeFirstFetch(existing: Novel?) {
        if (firstFetchTried) return
        val src = source ?: return // defer until onSourceReady
        firstFetchTried = true
        novelLog { "first-open fetch (no stored chapters)" }
        screenModelScope.launchIO {
            runCatching { fetchAndSync(src, existing) }.onFailure { e ->
                if (state.value !is NovelDetailsState.Loaded) {
                    mutableState.value = NovelDetailsState.Failed(e.message ?: "Failed to load novel")
                }
            }
        }
    }

    /** parseNovel + persist + sync. For a library novel [existing] is the stored row; defensively
     *  inserts a favorited row if the novel somehow isn't persisted yet. The reactive Flow then
     *  re-emits the updated chapter list. Returns the (added, removed) chapter diff for the refresh
     *  path's auto-download / removed-download handling; first-open and edit-info reset ignore it. */
    private suspend fun fetchAndSync(src: NovelSource, existing: Novel?): Pair<List<NovelChapter>, List<NovelChapter>> {
        // Parse the target novel's own url (the anchor's, or a grouped sibling's on a per-source edit).
        val sourceNovel = src.parseNovel(existing?.url ?: novelUrl)
        val target = if (existing != null) {
            // Refresh stored metadata from the source. A novel added from a browse / search list
            // carries only name + path + cover (no summary), and syncChaptersWithNovelSource touches
            // only chapters, so the description would stay blank without this. Prefer freshly-parsed
            // values, but never wipe existing data with a null/blank from a partial re-parse.
            val parsed = sourceNovel.toNovel(sourceId = src.id, favorite = existing.favorite)
            // Blank-safe: some plugins return an empty summary/field on a partial parse (e.g. a
            // selector mismatch), so a null OR blank parsed value must keep the existing data
            // rather than wipe it. Also respect Edit-info locks: a locked field keeps the user's
            // value instead of taking the source's.
            val f = existing.editedFlags
            val merged = existing.copy(
                author = if (f and EDITED_AUTHOR != 0) existing.author
                    else parsed.author?.takeIf { it.isNotBlank() } ?: existing.author,
                artist = if (f and EDITED_ARTIST != 0) existing.artist
                    else parsed.artist?.takeIf { it.isNotBlank() } ?: existing.artist,
                description = if (f and EDITED_DESCRIPTION != 0) existing.description
                    else parsed.description?.takeIf { it.isNotBlank() } ?: existing.description,
                genres = if (f and EDITED_GENRES != 0) existing.genres
                    else parsed.genres?.takeIf { it.isNotEmpty() } ?: existing.genres,
                status = if (f and EDITED_STATUS != 0) existing.status
                    else if (parsed.status != NovelStatusCode.UNKNOWN) parsed.status else existing.status,
                thumbnailUrl = parsed.thumbnailUrl?.takeIf { it.isNotBlank() } ?: existing.thumbnailUrl,
            )
            if (merged != existing) novelRepo.update(merged)
            merged
        } else {
            // Get-or-insert a non-favorite shadow row so a novel opened from Browse is viewable
            // without being silently added to the library; the "Add to library" toggle favorites it.
            // insertOrGet reuses a concurrently-created row (e.g. the browse reader's) instead of
            // duplicating, and the existing-row branch above preserves whatever favorite it holds.
            novelRepo.insertOrGet(sourceNovel.toNovel(sourceId = src.id, favorite = false))
                ?: return emptyList<NovelChapter>() to emptyList()
        }
        val chapters = sourceNovel.chapters.orEmpty()
        return if (chapters.isNotEmpty()) {
            syncChaptersWithNovelSource(chapters, target, src, chapterRepo, novelRepo, handler)
        } else {
            emptyList<NovelChapter>() to emptyList()
        }
    }

    // --- Favorite / categories ---

    /** Flip the library flag. Adding shows the category picker (the novel Flow re-emits favorite=true
     *  and rebuilds the heart; observeFromDb preserves the dialog across that re-emit). */
    fun toggleFavorite() {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val novel = loaded.novel
            val id = novel.id ?: return@launchIO
            if (!novel.favorite) {
                novelRepo.update(novel.copy(favorite = true))
                val allCategories = getNovelCategories.await().filter { (it.id ?: 0) > 0 }
                if (allCategories.isNotEmpty()) {
                    val currentIds = getNovelCategories.awaitByNovelId(id)
                        .mapNotNull { it.id?.toLong() }
                        .toSet()
                    mutableState.update {
                        (it as? NovelDetailsState.Loaded)
                            ?.copy(dialog = NovelDetailsDialog.ChangeCategory(allCategories, currentIds)) ?: it
                    }
                }
            } else {
                novelRepo.update(novel.copy(favorite = false))
            }
        }
    }

    /** Show the categories picker for a novel already in the library. */
    fun showChangeCategoryDialog() {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val id = loaded.novel.id ?: return@launchIO
            val allCategories = getNovelCategories.await().filter { (it.id ?: 0) > 0 }
            if (allCategories.isEmpty()) return@launchIO
            val currentIds = getNovelCategories.awaitByNovelId(id)
                .mapNotNull { it.id?.toLong() }
                .toSet()
            mutableState.update {
                (it as? NovelDetailsState.Loaded)
                    ?.copy(dialog = NovelDetailsDialog.ChangeCategory(allCategories, currentIds)) ?: it
            }
        }
    }

    fun applyCategories(categoryIds: List<Long>) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            setNovelCategories.await(loaded.novel.id, categoryIds)
            dismissDialog()
        }
    }

    /** The novel whose metadata the header shows + edits target: the viewed source's, or the anchor. */
    private fun displayedNovel(): Novel? = (state.value as? NovelDetailsState.Loaded)?.displayNovel

    /** A grouped sibling isn't watched by the anchor flow, so after editing its row refresh the
     *  snapshot and rebuild the header. Anchor edits re-emit through getByUrlAndSourceAsFlow already. */
    private suspend fun reSnapshotDisplayed(novelId: Long?) {
        novelId ?: return
        val anchor = (state.value as? NovelDetailsState.Loaded)?.novel ?: return
        if (novelId == anchor.id) return
        val fresh = novelRepo.getById(novelId) ?: return
        novelsById = novelsById.toMutableMap().apply { put(novelId, fresh) }
        rebuildLoaded(anchor, chaptersByNovel)
    }

    fun showEditNovelInfoDialog() {
        val n = displayedNovel() ?: return
        mutableState.update {
            (it as? NovelDetailsState.Loaded)?.copy(
                dialog = NovelDetailsDialog.EditInfo(
                    title = n.title,
                    author = n.author.orEmpty(),
                    artist = n.artist.orEmpty(),
                    description = n.description.orEmpty(),
                    genre = n.genres?.joinToString().orEmpty(),
                    status = n.status,
                ),
            ) ?: it
        }
    }

    /** Persist a manual metadata override. A blank field clears its lock so the source value wins
     *  again on the next refresh; a changed non-blank field locks it so the edit survives refresh;
     *  an unchanged field keeps its current lock state (saving the dialog doesn't lock everything). */
    fun updateNovelInfo(title: String?, author: String?, artist: String?, description: String?, genre: String?, status: Int?) {
        screenModelScope.launchIO {
            val n = displayedNovel() ?: return@launchIO
            var flags = n.editedFlags
            fun reconcile(bit: Int, newValue: String?, current: String?) {
                flags = when {
                    newValue == null -> flags and bit.inv()
                    newValue != current -> flags or bit
                    else -> flags
                }
            }
            reconcile(EDITED_AUTHOR, author, n.author)
            reconcile(EDITED_ARTIST, artist, n.artist)
            reconcile(EDITED_DESCRIPTION, description, n.description)
            reconcile(EDITED_GENRES, genre, n.genres?.joinToString())
            // Status: lock when overridden to a known value; UNKNOWN clears the lock (source wins again).
            flags = when {
                status == null || status == NovelStatusCode.UNKNOWN -> flags and EDITED_STATUS.inv()
                status != n.status -> flags or EDITED_STATUS
                else -> flags
            }
            val newStatus = if (status != null && status != NovelStatusCode.UNKNOWN) status else n.status
            val updated = n.copy(
                title = title ?: n.title,
                author = author ?: n.author,
                artist = artist ?: n.artist,
                description = description ?: n.description,
                genres = if (genre == null) n.genres
                else genre.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                status = newStatus,
                editedFlags = flags,
            )
            novelRepo.update(updated)
            dismissDialog()
            reSnapshotDisplayed(updated.id)
        }
    }

    /** Clear every metadata override and re-fetch source values (mirrors the manga reset-to-source).
     *  Passes the cleared novel straight to [fetchAndSync]: calling [refresh] here would re-merge
     *  against the not-yet-re-emitted stale state (locks still set), keeping the edits and even
     *  re-writing the old flags. */
    fun resetNovelInfo() {
        screenModelScope.launchIO {
            val n = displayedNovel() ?: return@launchIO
            val cleared = n.copy(editedFlags = 0)
            novelRepo.update(cleared)
            dismissDialog()
            // Re-fetch source values for the displayed source (anchor or sibling), then refresh the header.
            sourceManager.get(n.source)?.let { src -> runCatching { fetchAndSync(src, cleared) } }
            reSnapshotDisplayed(cleared.id)
        }
    }

    fun dismissDialog() {
        mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(dialog = null) ?: it }
    }

    // --- Chapter sort / filter / display ---
    // Each writes Novel.chapterFlags (or the global-default pref) and persists; the reactive flow
    // re-emits and observeFromDb re-sorts/re-filters. Local-bit setters mark the novel as having
    // its own override; the global setters drop the override so the (updated) default applies.

    fun setSortOrder(sort: Int, descending: Boolean) {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            var flags = setFlag(n.chapterFlags, sort, Manga.CHAPTER_SORTING_MASK)
            flags = setFlag(flags, if (descending) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_MASK)
            flags = setFlag(flags, Manga.CHAPTER_SORT_LOCAL, Manga.CHAPTER_SORT_LOCAL_MASK)
            novelRepo.update(n.copy(chapterFlags = flags))
        }
    }

    fun setFilters(read: Int, bookmarked: Int) {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            var flags = setFlag(n.chapterFlags, read, Manga.CHAPTER_READ_MASK)
            flags = setFlag(flags, bookmarked, Manga.CHAPTER_BOOKMARKED_MASK)
            flags = setFlag(flags, Manga.CHAPTER_FILTER_LOCAL, Manga.CHAPTER_FILTER_LOCAL_MASK)
            novelRepo.update(n.copy(chapterFlags = flags))
        }
    }

    fun setHideChapterTitles(hide: Boolean) {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            val display = if (hide) Manga.CHAPTER_DISPLAY_NUMBER else Manga.CHAPTER_DISPLAY_NAME
            var flags = setFlag(n.chapterFlags, display, Manga.CHAPTER_DISPLAY_MASK)
            // Display rides the sort-local bit (it's part of the same view group).
            flags = setFlag(flags, Manga.CHAPTER_SORT_LOCAL, Manga.CHAPTER_SORT_LOCAL_MASK)
            novelRepo.update(n.copy(chapterFlags = flags))
        }
    }

    fun setGlobalSort() {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            novelPreferences.defaultChapterSortOrder().set(loaded.sorting)
            novelPreferences.defaultChapterSortDescending().set(loaded.sortDescending)
            novelPreferences.defaultChapterHideTitles().set(loaded.hideChapterTitles)
            val flags = setFlag(loaded.novel.chapterFlags, Manga.CHAPTER_SORT_FILTER_GLOBAL, Manga.CHAPTER_SORT_LOCAL_MASK)
            novelRepo.update(loaded.novel.copy(chapterFlags = flags))
        }
    }

    fun setGlobalFilters() {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            novelPreferences.defaultChapterFilterUnread().set(loaded.readFilter)
            novelPreferences.defaultChapterFilterBookmarked().set(loaded.bookmarkedFilter)
            val flags = setFlag(loaded.novel.chapterFlags, Manga.CHAPTER_SORT_FILTER_GLOBAL, Manga.CHAPTER_FILTER_LOCAL_MASK)
            novelRepo.update(loaded.novel.copy(chapterFlags = flags))
        }
    }

    fun resetSortToDefault() {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            novelRepo.update(n.copy(chapterFlags = setFlag(n.chapterFlags, Manga.CHAPTER_SORT_FILTER_GLOBAL, Manga.CHAPTER_SORT_LOCAL_MASK)))
        }
    }

    fun resetFilterToDefault() {
        screenModelScope.launchIO {
            val n = (state.value as? NovelDetailsState.Loaded)?.novel ?: return@launchIO
            novelRepo.update(n.copy(chapterFlags = setFlag(n.chapterFlags, Manga.CHAPTER_SORT_FILTER_GLOBAL, Manga.CHAPTER_FILTER_LOCAL_MASK)))
        }
    }

    // --- Chapter selection + read/bookmark ---

    fun toggleSelection(chapterId: Long, selected: Boolean, fromLongPress: Boolean) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
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
        mutableState.value = loaded.copy(selection = newSelection)
    }

    fun selectAll() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        resetSelectionAnchors()
        mutableState.value = loaded.copy(selection = loaded.chapters.mapNotNull { it.id }.toSet())
    }

    fun invertSelection() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        resetSelectionAnchors()
        val inverted = loaded.chapters.mapNotNull { it.id }.filterNot { it in loaded.selection }.toSet()
        mutableState.value = loaded.copy(selection = inverted)
    }

    fun clearSelection() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        resetSelectionAnchors()
        if (loaded.selection.isNotEmpty()) mutableState.value = loaded.copy(selection = emptySet())
    }

    private fun resetSelectionAnchors() {
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun markSelectedRead(read: Boolean) {
        screenModelScope.launchIO {
            applyRead(selectedChapters() ?: return@launchIO, read)
            clearSelection()
        }
    }

    fun bookmarkSelected(bookmark: Boolean) {
        screenModelScope.launchIO {
            applyBookmark(selectedChapters() ?: return@launchIO, bookmark)
            clearSelection()
        }
    }

    // --- Downloads (bulk) ---
    // Pre-filter by the isDownloaded DB flag so a "download all" on a long novel doesn't stat
    // thousands of files on the caller; the manager still double-checks existence per chapter. Run
    // on IO since the manager's existence pre-check touches disk.

    fun downloadSelected() {
        val targets = selectedChapters() ?: return
        screenModelScope.launchIO { downloadManager.downloadChapters(targets.filter { !it.isDownloaded }) }
        clearSelection()
    }

    fun deleteSelectedDownloads() {
        val targets = selectedChapters() ?: return
        screenModelScope.launchIO { downloadManager.deleteChapters(targets.filter { it.isDownloaded }) }
        clearSelection()
    }

    fun downloadUnread() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        screenModelScope.launchIO { downloadManager.downloadChapters(loaded.chapters.filter { !it.read && !it.isDownloaded }) }
    }

    fun downloadAll() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        screenModelScope.launchIO { downloadManager.downloadChapters(loaded.chapters.filter { !it.isDownloaded }) }
    }

    /** Queue the next [count] not-yet-downloaded chapters in reading (source) order. */
    fun downloadNext(count: Int) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val next = loaded.chapters.sortedBy { it.sourceOrder }.filter { !it.isDownloaded }.take(count)
        screenModelScope.launchIO { downloadManager.downloadChapters(next) }
    }

    /** Stop the downloader and clear the whole pending queue (downloaded chapters are kept). */
    fun cancelDownloads() {
        downloadManager.cancelAllDownloads()
    }

    fun markAllRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            applyRead(loaded.chapters, read)
        }
    }

    /** Mark every chapter before the earliest selected one (in source order) read/unread. */
    fun markPreviousRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val ascending = loaded.chapters.sortedBy { it.sourceOrder }
            val earliest = ascending.indexOfFirst { it.id in loaded.selection }
            if (earliest > 0) applyRead(ascending.subList(0, earliest), read)
            clearSelection()
        }
    }

    private fun selectedChapters(): List<NovelChapter>? {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return null
        return loaded.chapters.filter { it.id in loaded.selection }
    }

    /** Stable cross-refresh identity for the hidden-chapters pref. */
    private fun hiddenKey(chapter: NovelChapter): String = "${chapter.novelId}|${chapter.url}"

    /** Hide the selected chapters (e.g. a source's own duplicate). Pref-backed, survives refresh. */
    fun hideSelected() {
        screenModelScope.launchIO {
            val targets = selectedChapters() ?: return@launchIO
            hiddenChaptersPref.set(hiddenChaptersPref.get() + targets.map { hiddenKey(it) })
            clearSelection()
        }
    }

    /** Unhide the selected chapters (only reachable while "Show hidden chapters" is on). */
    fun unhideSelected() {
        screenModelScope.launchIO {
            val targets = selectedChapters() ?: return@launchIO
            val keys = targets.map { hiddenKey(it) }.toSet()
            hiddenChaptersPref.set(hiddenChaptersPref.get().filterNot { it in keys }.toSet())
            clearSelection()
        }
    }

    fun toggleShowHidden() {
        showHiddenFlow.value = !showHiddenFlow.value
    }

    private data class ChapterReadSnapshot(val id: Long, val read: Boolean, val lastTextProgress: Int)

    /**
     * Mark [targets] (and their merged siblings) read/unread, then fire the side effects: unmarking
     * rewinds reading position; marking read optionally deletes downloads
     * ([NovelPreferences.removeAfterMarkedAsRead]). Every path offers an Undo restoring the pre-mark
     * read flag + progress.
     *
     * Download deletion is **deferred-commit**: the delete only runs when the snackbar dismisses, so
     * tapping Undo preserves the downloads with no re-fetch. This diverges from the manga side, where
     * the delete is immediate and Undo does not restore downloads; it's safe here because a novel
     * download is a single HTML file with no cache/CBZ cleanup to coordinate.
     */
    private suspend fun applyRead(targets: List<NovelChapter>, read: Boolean) {
        val expanded = expandToSiblings(targets)
        val snapshot = expanded.mapNotNull { ch -> ch.id?.let { ChapterReadSnapshot(it, ch.read, ch.lastTextProgress) } }
        if (snapshot.isEmpty()) return
        expanded.forEach { ch ->
            val id = ch.id ?: return@forEach
            if (ch.read != read) chapterRepo.setRead(id, read)
            if (!read && ch.lastTextProgress != 0) chapterRepo.setLastTextProgress(id, 0)
        }

        val toDelete = if (read && novelPreferences.removeAfterMarkedAsRead().get()) {
            expanded.filter { downloadManager.isChapterDownloaded(it) }
        } else {
            emptyList()
        }

        emitEvent(
            DetailsEvent.Snackbar(
                message = if (read) "Marked as read" else "Marked as unread",
                actionLabel = "Undo",
                onAction = { restoreReadProgress(snapshot) },
                onDismiss = { if (toDelete.isNotEmpty()) downloadManager.deleteChapters(toDelete) },
            ),
        )
    }

    /** Undo path for [applyRead]: restore the captured read flag + text progress per chapter. */
    private fun restoreReadProgress(snapshot: List<ChapterReadSnapshot>) {
        screenModelScope.launchIO {
            snapshot.forEach { snap ->
                chapterRepo.setRead(snap.id, snap.read)
                chapterRepo.setLastTextProgress(snap.id, snap.lastTextProgress)
            }
        }
    }

    private suspend fun applyBookmark(targets: List<NovelChapter>, bookmark: Boolean) {
        expandToSiblings(targets).forEach { if (it.bookmark != bookmark) it.id?.let { id -> chapterRepo.setBookmark(id, bookmark) } }
    }

    /** Whether chapter swipe gestures are enabled (Settings -> Reader). Reuses the shared manga pref. */
    fun isChapterSwipeEnabled(): Boolean = uiPreferences.enableChapterSwipeAction().get()

    /** Swipe a single chapter: flip its read / bookmark state. */
    fun toggleChapterRead(chapterId: Long) = toggleSingle(chapterId) { applyRead(listOf(it), !it.read) }

    fun toggleChapterBookmark(chapterId: Long) = toggleSingle(chapterId) { applyBookmark(listOf(it), !it.bookmark) }

    private inline fun toggleSingle(chapterId: Long, crossinline apply: suspend (NovelChapter) -> Unit) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val chapter = loaded.chapters.find { it.id == chapterId } ?: return@launchIO
            apply(chapter)
        }
    }

    /**
     * For a merged novel, expand a set of chapters to every sibling row sharing each chapter's
     * cross-source [NovelChapterAggregation.matchKey] (title-first, number fallback), so a read /
     * bookmark mark in the unified list lands on the same chapter in every grouped source. No-op for a
     * single source. Chapters with no usable key mark only their own row.
     */
    private fun expandToSiblings(chapters: List<NovelChapter>): List<NovelChapter> {
        if (chaptersByNovel.size <= 1) return chapters
        val keys = chapters.mapNotNullTo(HashSet()) { NovelChapterAggregation.matchKey(it) }
        if (keys.isEmpty()) return chapters
        val matched = chaptersByNovel.values.asSequence()
            .flatten()
            .filter { NovelChapterAggregation.matchKey(it) in keys }
        val unmatchable = chapters.filter { NovelChapterAggregation.matchKey(it) == null }
        return (matched + unmatchable).distinctBy { it.id }.toList()
    }

    /** Temporary verification aid (Phase 7), filterable via tag:NovelDetailsPort; stripped in P9. */
    private fun novelLog(message: () -> String) {
        if (BuildConfig.DEBUG) Logger.withTag("NovelDetailsPort").d(message())
    }
}

sealed interface NovelDetailsState {
    data object Loading : NovelDetailsState
    data class Loaded(
        /** The anchor novel (this screen's url/source). Favorite + identity key off it. */
        val novel: Novel,
        /** The novel whose metadata the header shows: the selected source's novel in a per-source chip
         *  view, otherwise the anchor. Drives title/author/artist/description/genres/status. */
        val displayNovel: Novel,
        val chapters: List<NovelChapter>,
        val isRefreshing: Boolean,
        val dialog: NovelDetailsDialog? = null,
        val selection: Set<Long> = emptySet(),
        val resumeChapter: NovelChapter? = null,
        val hasStarted: Boolean = false,
        /** Per-cover vibrant accent (ARGB Int) for the header backdrop; null when off / not extracted. */
        val accentColor: Int? = null,
        // Per-chapter in-flight download state (QUEUED/DOWNLOADING/ERROR) keyed by chapter id, mirrored
        // from the download engine's queue. A finished download drops out of this map; the chapter's
        // own `isDownloaded` flag then renders the DOWNLOADED check.
        val downloads: Map<Long, DetailsDownloadState> = emptyMap(),
        // Resolved chapter sort/filter/display values (per-novel override or global default).
        val sorting: Int = Manga.CHAPTER_SORTING_SOURCE,
        val sortDescending: Boolean = false,
        val readFilter: Int = Manga.SHOW_ALL,
        val bookmarkedFilter: Int = Manga.SHOW_ALL,
        val hideChapterTitles: Boolean = false,
        // Grouped-source chips for a merged novel (empty for a single source); the selected source
        // view (null = the unified stitched list).
        val sourceTabs: List<SourceTab> = emptyList(),
        val sourceView: Long? = null,
        // Header source line + status follow the viewed source: "Unified" for a merged novel's pooled
        // view, else the selected (or anchor) source's name and status.
        val sourceLabel: String = "",
        val displayStatus: Int = 0,
        // Hidden-chapter view: whether hidden chapters are revealed, which displayed ids are hidden
        // (rendered dimmed), and whether any chapter in the group is hidden (gates the overflow toggle).
        val showHidden: Boolean = false,
        val hiddenChapterIds: Set<Long> = emptySet(),
        val hasHiddenChapters: Boolean = false,
    ) : NovelDetailsState
    data class Failed(val message: String) : NovelDetailsState
}

/** One grouped-source chip: [novelId] is the sibling's DB id, [label] its source name. */
data class SourceTab(val novelId: Long, val label: String)

sealed interface NovelDetailsDialog {
    data class ChangeCategory(
        val allCategories: List<NovelCategory>,
        val currentCategoryIds: Set<Long>,
    ) : NovelDetailsDialog

    /** Initial field values for the Edit-info dialog. */
    data class EditInfo(
        val title: String,
        val author: String,
        val artist: String,
        val description: String,
        val genre: String,
        val status: Int,
    ) : NovelDetailsDialog

    /** Grouped sources for the Manage sources checklist (split / remove from library). */
    data class ManageSources(val sources: List<ManageSourceItem>) : NovelDetailsDialog

    /** Downloads orphaned when a refresh found chapters removed at the source; user confirms deletion. */
    data class ConfirmRemovedDownloads(val chapters: List<NovelChapter>) : NovelDetailsDialog
}

// Edit-info lock bits, persisted in Novel.editedFlags (must match the 38.sqm migration comment).
private const val EDITED_AUTHOR = 1
private const val EDITED_ARTIST = 1 shl 1
private const val EDITED_DESCRIPTION = 1 shl 2
private const val EDITED_GENRES = 1 shl 3
private const val EDITED_STATUS = 1 shl 4
