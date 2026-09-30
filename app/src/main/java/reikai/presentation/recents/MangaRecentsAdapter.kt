package reikai.presentation.recents

import android.content.Context
import android.content.Intent
import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.chapter.hiddenChapterKey
import reikai.domain.entry.EntryId
import reikai.domain.entry.overlayCustomInfo
import reikai.domain.entry.withCustomInfo
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.manga.inReadingOrder
import reikai.domain.merge.ChapterCopyRow
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.reader.ChapterProgress
import reikai.domain.recents.RECENTS_FEED_LIMIT
import reikai.domain.recents.RecentlyAddedManga
import reikai.domain.recents.RecentlyAddedRepository
import reikai.domain.recents.RecentsUnreadRepository
import reikai.domain.recents.recentsFeedCutoff
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.decideAdd
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/**
 * Adapts Mihon's two live models to the neutral [RecentsProvider]. Both stay live and upstream-tracked
 * (never made to implement a Reikai interface); this maps their rows and forwards each verb on.
 *
 * A model is absent where the surface renders no lane needing it, which keeps a History tab from
 * building the updates model and running its query. Nothing reaches an absent one: the engine asks
 * only for the lanes it renders, and [chapterActions] comes from the graph, not from either model.
 */
@AssistedInject
class MangaRecentsAdapter(
    // Assisted: the models belong to the surface that is composing, so only the call site has them.
    @Assisted private val updatesModel: UpdatesViewModel?,
    @Assisted private val historyModel: HistoryViewModel?,
    @Assisted private val surface: RecentsSurface,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val recentlyAdded: RecentlyAddedRepository,
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val recentsUnread: RecentsUnreadRepository,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    // Read from the preference rather than off the model, whose copy is a Compose State the engine
    // cannot collect.
    private val libraryPreferences: LibraryPreferences,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val mergeManager: MangaMergeManager,
    private val mergedChapterProvider: MergedChapterProvider,
    private val mergedChapterUnits: MergedChapterUnitRepository,
    private val mangaPreferences: MangaPreferences,
    private val getManga: GetManga,
    private val mangaLibraryAdder: MangaLibraryAdder,
    private val application: Context,
    override val chapterActions: MangaRecentsChapterActions,
) : RecentsProvider {

    /**
     * One entry point per surface, so the models a surface holds and the surface it says it is cannot
     * disagree, and neither can a caller ask for an adapter with no models at all. [create] is only
     * public because Metro's assisted factories cannot hide it; call the three named ones.
     */
    @AssistedFactory
    interface Factory {
        fun create(
            updatesModel: UpdatesViewModel?,
            historyModel: HistoryViewModel?,
            surface: RecentsSurface,
        ): MangaRecentsAdapter

        fun forUpdates(updatesModel: UpdatesViewModel) =
            create(updatesModel, historyModel = null, surface = RecentsSurface.UPDATES)

        fun forHistory(historyModel: HistoryViewModel) =
            create(updatesModel = null, historyModel = historyModel, surface = RecentsSurface.HISTORY)

        fun forRecents(updatesModel: UpdatesViewModel, historyModel: HistoryViewModel) =
            create(updatesModel, historyModel, surface = RecentsSurface.RECENTS)
    }

    override val contentType = ContentType.MANGA

    override val typeCapabilities = setOf(RecentsTypeCapability.UPCOMING, RecentsTypeCapability.SCANLATOR_FILTER)

    // A null list is this model's "no emission yet", where the updates model carries a loading flag.
    // Lazy so a surface that renders neither lane never touches the model it was not given.
    override val readLane: Flow<RecentsLaneRows> by lazy {
        val rows = historyRows().state.map { state ->
            RecentsLaneRows(
                items = state.list.orEmpty().map { it.toRecentsItem() },
                loaded = state.list != null,
            )
        }
        readCopies.lane(rows, membership) { ids -> mergedChapterUnits.getCopiesAsFlow(ContentType.MANGA, ids) }
    }

    /** The read lane's grouped rows' copies, which their download state is drawn over. */
    private val readCopies = RecentsRowCopiesIndex()

    override val updatedLane: Flow<RecentsLaneRows> by lazy {
        updatesRows().state.map { state ->
            RecentsLaneRows(items = state.items.map { it.toRecentsItem() }, loaded = !state.isLoading)
        }
    }

    private fun historyRows() = requireNotNull(historyModel) { "$surface renders no read lane" }

    private fun updatesRows() = requireNotNull(updatesModel) { "$surface renders no updated lane" }

    // The only lane with no model behind it: nothing rendered a newly-added feed before this surface.
    override val addedLane: Flow<RecentsLaneRows> =
        sourcePreferences.recentsCategoryFilterFlow(surface).flatMapLatest { categories ->
            combine(
                recentlyAdded.subscribeManga(
                    after = recentsFeedCutoff(),
                    limit = RECENTS_FEED_LIMIT,
                    includedCategories = categories.include,
                    excludedCategories = categories.exclude,
                ),
                getCustomMangaInfo.subscribeAll(),
            ) { rows, customInfo ->
                rows.overlayCustomInfo(
                    customInfo.associateBy { it.mangaId },
                    RecentlyAddedManga::mangaId,
                    RecentlyAddedManga::withCustomInfo,
                ).map { it.toRecentsItem() }
            }
        }.asLane()

    override val unreadEntries: Flow<Set<EntryId>> =
        reikaiLibraryPreferences.seriesMergingEnabled.changes()
            .flatMapLatest { recentsUnread.subscribeMangaIdsWithUnread(mergingEnabled = it) }
            .map { ids -> ids.mapTo(HashSet(), EntryId::Manga) }

    override val chapterWrites: Flow<Unit> = recentsUnread.mangaChapterWrites()

    override val lastUpdated: Flow<Long> = libraryPreferences.lastUpdatedTimestamp.changes()

    override val updating: Flow<Boolean> = LibraryUpdateJob.isRunningFlow(application)

    override val membership: Flow<Map<EntryId, Long>> =
        mergeManager.membershipFlow(reikaiLibraryPreferences.seriesMergingEnabled, EntryId::Manga)

    override val downloadChanges: Flow<Unit> = merge(
        downloadCache.changes,
        downloadManager.queueState.map { },
        downloadManager.statusFlow().map { },
        readCopies.changes,
    )

    override val progressChanges: Flow<Unit> = downloadManager.progressFlow().map { }

    override suspend fun targetChapter(item: RecentsItem): ChapterRef? =
        resolveTarget(item)?.let { (target, _) -> ChapterRef(item.entryId, target.chapterId) }

    override suspend fun targetRow(item: RecentsItem): RecentsTargetRow? {
        val (resolved, mangaById) = resolveTarget(item) ?: return null
        val chapter = resolved.chapters[resolved.chapterId] ?: return null
        // Not necessarily this row's manga: a merged row resolves across the group, and the download
        // lookup is keyed by the owner's stored title and source.
        val owner = mangaById[chapter.mangaId] ?: return null
        val unitOf = resolved.stitch.associateBy { it.chapterId }
        val copies = recentsRowCopies(chapter, resolved.stitch, resolved.pooled) { it.id }.mapNotNull { copy ->
            val copyOwner = mangaById[copy.mangaId] ?: return@mapNotNull null
            ChapterCopyRow(
                namedId = chapter.id,
                copy = unitOf[copy.id] ?: ChapterUnit(copy.id, unit = 0, copyOrder = 0),
                ownerTitle = copyOwner.title,
                ownerSource = copyOwner.source.toString(),
                chapterName = copy.name,
                scanlator = copy.scanlator,
                chapterUrl = copy.url,
            )
        }
        return RecentsTargetRow(
            ref = ChapterRef(EntryId.Manga(owner.id), chapter.id),
            chapter = item.lane.chapterLabel(chapter.name, chapter.chapterNumber),
            state = chapterState(
                read = chapter.read || chapter.id in resolved.readElsewhere,
                bookmark = chapter.bookmark || chapter.id in resolved.bookmarkedElsewhere,
                progress = ChapterProgress.Pages(chapter.lastPageRead, chapter.pageCount),
            ),
            // The copy a tap opens, which on a group-scoped lane can be another source's on disk.
            download = copiesDownloadUi(item.lane, chapter.id) { copies },
        )
    }

    /**
     * The lane's target over the group, with the group's members by id, which a copy's download is looked
     * up under. Resolved per rendered row rather than at assembly, which is what keeps a five-hundred-row
     * feed from paying a chapter query per row on every emission.
     */
    private suspend fun resolveTarget(item: RecentsItem): Pair<RecentsTarget<Chapter>, Map<Long, Manga>>? {
        val mangaId = item.entryId.rawId
        val manga = getManga.await(mangaId)
        val group = manga?.let { mergedChapterProvider.load(it) }
        val hidden = mangaPreferences.hiddenChapters().get()
        val target = resolveRecentsTarget(
            lane = item.lane,
            group = readingOrder(manga, group?.chapters),
            pooled = group?.pooledChapters.orEmpty(),
            stitch = group?.stitch.orEmpty(),
            ownSource = { readingOrder(manga, getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)) },
            id = { it.id },
            read = { it.read },
            bookmark = { it.bookmark },
            isHidden = { chapter ->
                val owner = group?.mangaById?.get(chapter.mangaId) ?: manga
                owner != null && hiddenChapterKey(owner.source.toString(), chapter.url) in hidden
            },
        ) ?: return null
        return target to group?.mangaById.orEmpty()
    }

    /** Ascending reading order, which every shared target rule expects: the one the reader pages in. */
    private fun readingOrder(manga: Manga?, chapters: List<Chapter>?): List<Chapter> =
        if (manga == null || chapters == null) chapters.orEmpty() else chapters.inReadingOrder(manga)

    override suspend fun latestRead(): RecentsItem? = historyModel?.getLast()?.toRecentsItem()

    override fun removeFromHistory(entries: Set<EntryId>) {
        entries.filterIsInstance<EntryId.Manga>().forEach { historyModel?.removeAllFromHistory(it.rawId) }
    }

    override fun removeHistoryRecord(item: RecentsItem) {
        val record = item.payload as? HistoryWithRelations ?: return
        historyModel?.removeFromHistory(record)
    }

    // The add flow answers rather than prompts: the engine owns the surface's one dialog slot, so
    // everything below reports what it found and lets the engine decide what to ask.
    private suspend fun mangaOf(entry: EntryId): Manga? =
        (entry as? EntryId.Manga)?.let { getManga.await(it.rawId) }

    override suspend fun addDecision(entry: EntryId): AddDecision<RecentsDuplicates>? {
        val manga = mangaOf(entry) ?: return null
        return decideAdd(inLibrary = manga.favorite) {
            val duplicates = mangaLibraryAdder.getDuplicates(manga)
            if (duplicates.isEmpty()) return@decideAdd null

            val labels = mangaLibraryAdder.duplicateSourceLabels(duplicates)
            RecentsDuplicates(
                duplicates = duplicates.map {
                    RecentsDuplicate(EntryId.Manga(it.manga.id), it.toDuplicateCard(labels))
                },
                groupIdByRawId = mangaLibraryAdder.getDuplicateGroupIds(duplicates),
                suggestGroup = mangaLibraryAdder.suggestGrouping,
            )
        }
    }

    override suspend fun addToLibrary(entry: EntryId): AddFavoriteResult {
        val manga = mangaOf(entry) ?: return AddFavoriteResult.Failed
        // Already there: the shared add toggles the favorite, so running it again would remove the
        // entry and reset its dateAdded. The engine's remove branch normally catches this first.
        if (manga.favorite) return AddFavoriteResult.Added
        return mangaLibraryAdder.resolveAddFavorite(manga)
    }

    override suspend fun applyAddCategories(entry: EntryId, categoryIds: List<Long>, joinGroup: List<EntryId>) {
        val manga = mangaOf(entry) ?: return
        if (joinGroup.isEmpty()) {
            mangaLibraryAdder.confirmAddCategories(manga.id, categoryIds)
        } else {
            mangaLibraryAdder.confirmGroupCategories(manga, joinGroup.map { it.rawId }, categoryIds)
        }
    }

    override suspend fun addToGroup(entry: EntryId, duplicates: List<EntryId>): AddFavoriteResult {
        val manga = mangaOf(entry) ?: return AddFavoriteResult.Failed
        return mangaLibraryAdder.addToExistingGroup(manga, duplicates.map { it.rawId })
    }

    override suspend fun clearHistory(): Boolean = historyModel?.removeAllHistory() == true

    // Straight to the job rather than through the updates model, which only wraps this same call in a
    // snackbar event the shell now owns. It also lets a surface with no updated lane still refresh.
    override fun refresh(): Boolean = LibraryUpdateJob.startNow(application.workManager)

    override suspend fun detailsScreen(entry: EntryId): Screen? =
        (entry as? EntryId.Manga)?.let { MangaScreen(it.rawId) }

    override fun open(item: RecentsItem, chapter: ChapterRef): Intent = ReaderActivity.newIntent(
        application,
        item.entryId.rawId,
        chapter.chapterId,
        sourceScoped = item.lane.sourceScoped,
    )

    override fun rowUi(item: RecentsItem): RecentsRowUi = mangaRowUi(item)

    override fun downloadUi(item: RecentsItem): RecentsDownloadUi? = when (val payload = item.payload) {
        is HistoryWithRelations -> historyDownloadUi(item.lane, payload)
        else -> mangaDownloadUi(item)
    }

    /**
     * The read lane has no model computing this per row, so it asks the queue and the on-disk index
     * itself, over the row's copies loaded with the lane ([readCopies]). Polled rather than carried on
     * the row, because combining the whole feed with the download queue would re-map every row of it
     * on each download tick. The Downloaded filter asks this same state.
     */
    private fun historyDownloadUi(lane: RecentsLane, payload: HistoryWithRelations) =
        copiesDownloadUi(lane, payload.chapterId) {
            with(payload) {
                readCopies.copiesOf(chapterId, storedTitle, sourceId.toString(), chapterName, scanlator, chapterUrl)
            }
        }

    private fun copiesDownloadUi(lane: RecentsLane, chapterId: Long, copies: () -> List<ChapterCopyRow>) =
        recentsCopiesDownloadUi(
            lane,
            chapterId,
            copies,
            queued = { downloadManager.getQueuedDownloadOrNull(chapterId)?.status },
            progress = RecentsDownloadProgress.Live {
                downloadManager.getQueuedDownloadOrNull(chapterId)?.progress ?: 0
            },
        ) { copy ->
            downloadManager.isChapterDownloaded(
                copy.chapterName,
                copy.scanlator,
                copy.chapterUrl,
                copy.ownerTitle,
                copy.ownerSource.toLong(),
            )
        }
}

/** The updates model already builds both providers per row, so this only hands them over. */
internal fun mangaDownloadUi(item: RecentsItem): RecentsDownloadUi? = when (val payload = item.payload) {
    is UpdatesItem -> RecentsDownloadUi(
        state = payload.downloadStateProvider,
        progress = RecentsDownloadProgress.Live(payload.downloadProgressProvider),
    )
    else -> null
}

/**
 * Free rather than a member for the same reason the item mappers below are: an adapter carries the
 * surface's live models, so a test can reach this without standing one up.
 */
internal fun mangaRowUi(item: RecentsItem): RecentsRowUi = when (val payload = item.payload) {
    is UpdatesItem -> RecentsRowUi(
        cover = payload.update.coverData,
        title = payload.update.mangaTitle,
        // updatesView is favorite-gated, so a row on this lane is always in the library.
        isFavorite = true,
        chapter = RecentsChapterUi.Named(payload.update.chapterName),
        state = chapterState(
            read = payload.update.read,
            bookmark = payload.update.bookmark,
            progress = ChapterProgress.Pages(payload.update.lastPageRead, payload.update.pageCount),
        ),
    )
    is HistoryWithRelations -> RecentsRowUi(
        cover = payload.coverData,
        title = payload.title,
        // historyView is not favorite-gated: a read entry may never have been added.
        isFavorite = payload.coverData.isMangaFavorite,
        chapter = RecentsChapterUi.Number(payload.chapterNumber),
        state = chapterState(
            read = payload.read,
            bookmark = payload.bookmark,
            progress = ChapterProgress.Pages(payload.lastPageRead, payload.pageCount),
        ),
    )
    is RecentlyAddedManga -> RecentsRowUi(
        cover = payload.coverData,
        title = payload.title,
        isFavorite = true,
        chapter = null,
        state = null,
    )
    else -> EMPTY_RECENTS_ROW
}

internal fun UpdatesItem.toRecentsItem(): RecentsItem = RecentsItem(
    entryId = EntryId.Manga(update.mangaId),
    timestamp = update.dateFetch,
    lane = RecentsLane.Updated(ChapterRef(EntryId.Manga(update.mangaId), update.chapterId)),
    payload = this,
)

// readAt is a java.util.Date here and a Long on the novel side; the divergence dies at this seam.
internal fun HistoryWithRelations.toRecentsItem(): RecentsItem = RecentsItem(
    entryId = EntryId.Manga(mangaId),
    timestamp = readAt?.time ?: 0L,
    lane = RecentsLane.Read(ChapterRef(EntryId.Manga(mangaId), chapterId)),
    payload = this,
)

internal fun RecentlyAddedManga.toRecentsItem(): RecentsItem = RecentsItem(
    entryId = EntryId.Manga(mangaId),
    timestamp = dateAdded,
    lane = RecentsLane.Added,
    payload = this,
)
