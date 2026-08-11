package reikai.presentation.recents

import android.app.Application
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.recents.RecentlyAddedNovel
import reikai.domain.recents.RecentlyAddedRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.decideAdd
import reikai.presentation.history.NovelHistoryViewModel
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.reader.NovelReaderScreen
import reikai.presentation.updates.NovelUpdatesItem
import reikai.presentation.updates.NovelUpdatesViewModel
import uy.kohesive.injekt.injectLazy
import kotlin.time.Clock

/**
 * The novel twin of [MangaRecentsAdapter], over Reikai's two novel models. These two dissolve into
 * this adapter at the cutover, where the manga pair stays live behind theirs; until then both sides
 * are wrapped the same way so the seam is symmetric.
 */
class NovelRecentsAdapter private constructor(
    private val updatesModel: NovelUpdatesViewModel?,
    private val historyModel: NovelHistoryViewModel?,
    private val surface: RecentsSurface,
) : RecentsProvider {

    /** One entry point per surface, the twin of [MangaRecentsAdapter]'s. */
    companion object {
        fun forUpdates(updatesModel: NovelUpdatesViewModel) =
            NovelRecentsAdapter(updatesModel, historyModel = null, surface = RecentsSurface.UPDATES)

        fun forHistory(historyModel: NovelHistoryViewModel) =
            NovelRecentsAdapter(updatesModel = null, historyModel = historyModel, surface = RecentsSurface.HISTORY)

        fun forRecents(updatesModel: NovelUpdatesViewModel, historyModel: NovelHistoryViewModel) =
            NovelRecentsAdapter(updatesModel, historyModel, surface = RecentsSurface.RECENTS)
    }

    private val sourcePreferences: ReikaiSourcePreferences by injectLazy()
    private val recentlyAdded: RecentlyAddedRepository by injectLazy()
    private val getNextNovelChapter: GetNextNovelChapter by injectLazy()
    private val chapterRepository: NovelChapterRepository by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()
    private val mergeManager: NovelMergeManager by injectLazy()
    private val novelLibraryAdder: NovelLibraryAdder by injectLazy()
    private val application: Application by injectLazy()

    override val contentType = ContentType.NOVELS

    // Lazy so a surface that renders neither lane never touches the model it was not given.
    override val readLane: Flow<RecentsLaneRows> by lazy {
        historyRows().state.map { state ->
            RecentsLaneRows(
                items = state.list.orEmpty().map { it.toRecentsItem() },
                loaded = state.list != null,
            )
        }
    }

    override val updatedLane: Flow<RecentsLaneRows> by lazy {
        updatesRows().state.map { state ->
            RecentsLaneRows(items = state.items.map { it.toRecentsItem() }, loaded = !state.isLoading)
        }
    }

    private fun historyRows() = requireNotNull(historyModel) { "$surface renders no read lane" }

    private fun updatesRows() = requireNotNull(updatesModel) { "$surface renders no updated lane" }

    override val addedLane: Flow<RecentsLaneRows> =
        sourcePreferences.recentsCategoryFilterFlow(surface).flatMapLatest { categories ->
            recentlyAdded.subscribeNovels(
                after = addedLaneCutoff(),
                limit = ADDED_LANE_LIMIT,
                includedCategories = categories.include,
                excludedCategories = categories.exclude,
            ).map { rows -> rows.map { it.toRecentsItem() } }
        }.asLane()

    override val lastUpdated: Flow<Long> = novelPreferences.novelLibraryUpdateLastTimestamp().changes()

    override val updating: Flow<Boolean> = NovelUpdateJob.isRunningFlow(application)

    override val membership: Flow<Map<EntryId, Long>> =
        mergeManager.membershipFlow(reikaiLibraryPreferences.seriesMergingEnabled, EntryId::Novel)

    /** Merge-aware on all three lanes, the twin of [MangaRecentsAdapter.targetChapter]. */
    override suspend fun targetChapter(item: RecentsItem): ChapterRef? {
        val novelId = item.entryId.rawId
        // Already ascending reading order, which is the contract every rule below reads under.
        val group = getNextNovelChapter.groupChapters(novelId)
        val groupChapters = group.chapters.map { it.toRecentsChapter(group.readInOtherSources) }
        val chapterId = when (val lane = item.lane) {
            is RecentsLane.Read -> resumeInGroup(groupChapters, lane.chapter.chapterId)
                // A recorded chapter the cross-source stitch dropped resumes from its own source.
                ?: getNextNovelChapter.await(novelId, lane.chapter.chapterId)?.id
            is RecentsLane.Updated -> firstUnreadInBurst(
                // Source order is this type's reading order, which is what getByNovelId returns. The
                // burst stays within one source; only the read-elsewhere carry-over crosses the group.
                chapters = chapterRepository.getByNovelId(novelId)
                    .map { it.toRecentsChapter(group.readInOtherSources) },
                rowChapterId = lane.chapter.chapterId,
            )
            // Same fallback as the manga twin: the cross-source stitch can drop this novel's own
            // chapters, and without it a merged row on this lane resolves nothing and the tap dies.
            RecentsLane.Added -> firstUnreadOf(groupChapters)
                ?: getNextNovelChapter.awaitFirstUnread(novelId)?.id
        }
        return chapterId?.let { ChapterRef(item.entryId, it) }
    }

    private fun NovelChapter.toRecentsChapter(readInOtherSources: Set<Long>) = RecentsChapter(
        id = id,
        fetchedAt = dateFetch,
        read = read || id in readInOtherSources,
    )

    /** Present only where the updates model is, the twin of [MangaRecentsAdapter.chapterActions]. */
    override val chapterActions: RecentsChapterActions? = updatesModel?.let(::ModelChapterActions)

    private class ModelChapterActions(private val model: NovelUpdatesViewModel) : RecentsChapterActions {

        private fun Set<ChapterRef>.ownItems(): List<NovelUpdatesItem> {
            val ids = filter { it.entryId is EntryId.Novel }.mapTo(HashSet()) { it.chapterId }
            if (ids.isEmpty()) return emptyList()
            return model.state.value.items.filter { it.update.chapterId in ids }
        }

        override fun markRead(chapters: Set<ChapterRef>, read: Boolean) {
            model.markRead(chapters.ownItems(), read)
        }

        override fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) {
            model.bookmark(chapters.ownItems(), bookmarked)
        }

        // Per row rather than in one call: this model's batch entry point only ever queues, and the
        // row indicator also cancels, expedites and deletes.
        override fun download(chapters: Set<ChapterRef>, action: ChapterDownloadAction) {
            chapters.ownItems().forEach { model.onDownloadAction(it, action) }
        }

        override fun deleteDownloads(chapters: Set<ChapterRef>) {
            model.deleteChapters(chapters.ownItems())
        }
    }

    override fun removeFromHistory(entries: Set<EntryId>) {
        entries.filterIsInstance<EntryId.Novel>().forEach { historyModel?.removeAllFromHistory(it.rawId) }
    }

    override fun removeHistoryRecord(item: RecentsItem) {
        val record = item.payload as? NovelHistoryWithRelations ?: return
        historyModel?.removeFromHistory(record)
    }

    private suspend fun novelOf(entry: EntryId): Novel? =
        (entry as? EntryId.Novel)?.let { novelRepository.getById(it.rawId) }

    override suspend fun addDecision(entry: EntryId): AddDecision<RecentsDuplicates>? {
        val novel = novelOf(entry) ?: return null
        return decideAdd(inLibrary = novel.favorite) {
            novelLibraryAdder.findDuplicates(novel.id, novel.title)?.let { found ->
                RecentsDuplicates(
                    duplicates = found.duplicates.map {
                        RecentsDuplicate(
                            EntryId.Novel(it.novel.id),
                            it.toDuplicateCard(found.sourceLabels, found.sourceSites),
                        )
                    },
                    groupIdByRawId = novelLibraryAdder.getDuplicateGroupIds(found.duplicates),
                    suggestGroup = novelLibraryAdder.suggestGrouping,
                )
            }
        }
    }

    override suspend fun addToLibrary(entry: EntryId): AddFavoriteResult {
        val novel = novelOf(entry) ?: return AddFavoriteResult.Failed
        // Same guard as the manga twin: re-adding a row that is already in the library would refile
        // its categories over whatever the user has since chosen.
        if (novel.favorite) return AddFavoriteResult.Added
        return novelLibraryAdder.addStoredToLibrary(novel.id)
    }

    override suspend fun applyAddCategories(entry: EntryId, categoryIds: List<Long>) {
        val novel = novelOf(entry) ?: return
        novelLibraryAdder.confirmAddCategories(novel.id, categoryIds)
    }

    override suspend fun addToGroup(entry: EntryId, duplicates: List<EntryId>): AddFavoriteResult {
        val novel = novelOf(entry) ?: return AddFavoriteResult.Failed
        return novelLibraryAdder.addToExistingGroup(novel.id, duplicates.map { it.rawId })
    }

    override fun clearHistory() {
        historyModel?.removeAllHistory()
    }

    // Straight to the job, the twin of the manga side and for the same two reasons.
    override fun refresh(): Boolean = NovelUpdateJob.startNow(application)

    override suspend fun detailsScreen(entry: EntryId): Screen? {
        val novelId = (entry as? EntryId.Novel)?.rawId ?: return null
        val novel = novelRepository.getById(novelId) ?: return null
        return NovelScreen(novel.source, novel.url)
    }

    // No lookup, unlike detailsScreen: the novel reader is keyed by id, not by source and url.
    override suspend fun open(item: RecentsItem): RecentsOpen? {
        val target = targetChapter(item) ?: return null
        return RecentsOpen.ReaderScreen(
            NovelReaderScreen(item.entryId.rawId, target.chapterId, sourceScoped = item.lane.sourceScoped),
        )
    }

    override fun rowUi(item: RecentsItem): RecentsRowUi = novelRowUi(item)

    override fun downloadUi(item: RecentsItem): RecentsDownloadUi? = novelDownloadUi(item)
}

/**
 * The novel row stores a state rather than a provider, and cannot answer byte progress at all until
 * the download subsystems merge, which it declares rather than reporting a zero the renderer could
 * not tell from a download that has genuinely started.
 */
internal fun novelDownloadUi(item: RecentsItem): RecentsDownloadUi? = when (val payload = item.payload) {
    is NovelUpdatesItem -> RecentsDownloadUi(
        state = { payload.downloadState },
        progress = RecentsDownloadProgress.Unsupported,
    )
    else -> null
}

/** Free for the same reason as its manga twin: an adapter cannot be built in a unit test. */
internal fun novelRowUi(item: RecentsItem): RecentsRowUi = when (val payload = item.payload) {
    is NovelUpdatesItem -> RecentsRowUi(
        cover = payload.update.coverData,
        title = payload.update.novelTitle,
        // novelUpdatesView is favorite-gated, so a row on this lane is always in the library.
        isFavorite = true,
        chapter = namedChapter(
            name = payload.update.chapterName,
            read = payload.update.read,
            bookmark = payload.update.bookmark,
            progress = RecentsProgress.Percent(payload.update.lastTextProgress),
        ),
    )
    is NovelHistoryWithRelations -> RecentsRowUi(
        cover = payload.coverData,
        title = payload.title,
        // novelHistoryView is not favorite-gated: a read entry may never have been added.
        isFavorite = payload.coverData.isNovelFavorite,
        chapter = RecentsChapterUi.Number(payload.chapterNumber),
    )
    is RecentlyAddedNovel -> RecentsRowUi(
        cover = payload.coverData,
        title = payload.title,
        isFavorite = true,
        chapter = null,
    )
    else -> EMPTY_RECENTS_ROW
}

internal const val ADDED_LANE_LIMIT = 500L
private const val ADDED_LANE_MONTHS = 3L

/** The added lane matches the updated lane's bound; nothing bounds a library on its own. */
internal fun addedLaneCutoff(): Long = Clock.System.now()
    .minus(ADDED_LANE_MONTHS, DateTimeUnit.MONTH, TimeZone.currentSystemDefault())
    .toEpochMilliseconds()

internal fun NovelUpdatesItem.toRecentsItem(): RecentsItem = RecentsItem(
    entryId = EntryId.Novel(update.novelId),
    timestamp = update.dateFetch,
    lane = RecentsLane.Updated(ChapterRef(EntryId.Novel(update.novelId), update.chapterId)),
    payload = this,
)

internal fun NovelHistoryWithRelations.toRecentsItem(): RecentsItem = RecentsItem(
    entryId = EntryId.Novel(novelId),
    timestamp = readAt ?: 0L,
    lane = RecentsLane.Read(ChapterRef(EntryId.Novel(novelId), chapterId)),
    payload = this,
)

internal fun RecentlyAddedNovel.toRecentsItem(): RecentsItem = RecentsItem(
    entryId = EntryId.Novel(novelId),
    timestamp = dateAdded,
    lane = RecentsLane.Added,
    payload = this,
)
