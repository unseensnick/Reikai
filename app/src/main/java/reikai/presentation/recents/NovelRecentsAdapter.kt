package reikai.presentation.recents

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
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
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.recents.RecentlyAddedNovel
import reikai.domain.recents.RecentlyAddedRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.history.NovelHistoryViewModel
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.updates.NovelUpdatesItem
import reikai.presentation.updates.NovelUpdatesViewModel
import uy.kohesive.injekt.injectLazy
import kotlin.time.Clock

/**
 * The novel twin of [MangaRecentsAdapter], over Reikai's two novel models. These two dissolve into
 * this adapter at the cutover, where the manga pair stays live behind theirs; until then both sides
 * are wrapped the same way so the seam is symmetric.
 */
class NovelRecentsAdapter(
    private val updatesModel: NovelUpdatesViewModel,
    private val historyModel: NovelHistoryViewModel,
    private val surface: RecentsSurface,
) : RecentsProvider {

    private val sourcePreferences: ReikaiSourcePreferences by injectLazy()
    private val recentlyAdded: RecentlyAddedRepository by injectLazy()
    private val getNextNovelChapter: GetNextNovelChapter by injectLazy()
    private val chapterRepository: NovelChapterRepository by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()
    private val mergeManager: NovelMergeManager by injectLazy()

    override val contentType = ContentType.NOVELS

    override val readLane: Flow<RecentsLaneRows> = historyModel.state.map { state ->
        RecentsLaneRows(
            items = state.list.orEmpty().map { it.toRecentsItem() },
            loaded = state.list != null,
        )
    }

    override val updatedLane: Flow<RecentsLaneRows> = updatesModel.state.map { state ->
        RecentsLaneRows(items = state.items.map { it.toRecentsItem() }, loaded = !state.isLoading)
    }

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
            RecentsLane.Added -> firstUnreadOf(groupChapters)
        }
        return chapterId?.let { ChapterRef(item.entryId, it) }
    }

    private fun NovelChapter.toRecentsChapter(readInOtherSources: Set<Long>) = RecentsChapter(
        id = id,
        fetchedAt = dateFetch,
        read = read || id in readInOtherSources,
    )

    private fun Set<ChapterRef>.ownItems(): List<NovelUpdatesItem> {
        val ids = filter { it.entryId is EntryId.Novel }.mapTo(HashSet()) { it.chapterId }
        if (ids.isEmpty()) return emptyList()
        return updatesModel.state.value.items.filter { it.update.chapterId in ids }
    }

    override fun markRead(chapters: Set<ChapterRef>, read: Boolean) {
        updatesModel.markRead(chapters.ownItems(), read)
    }

    override fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) {
        updatesModel.bookmark(chapters.ownItems(), bookmarked)
    }

    override fun download(chapters: Set<ChapterRef>) {
        updatesModel.downloadChapters(chapters.ownItems())
    }

    override fun deleteDownloads(chapters: Set<ChapterRef>) {
        updatesModel.deleteChapters(chapters.ownItems())
    }

    override fun removeFromHistory(entries: Set<EntryId>) {
        entries.filterIsInstance<EntryId.Novel>().forEach { historyModel.removeAllFromHistory(it.rawId) }
    }

    override fun clearHistory() {
        historyModel.removeAllHistory()
    }

    override fun refresh(): Boolean = updatesModel.updateLibrary()

    override suspend fun detailsScreen(entry: EntryId): Screen? {
        val novelId = (entry as? EntryId.Novel)?.rawId ?: return null
        val novel = novelRepository.getById(novelId) ?: return null
        return NovelScreen(novel.source, novel.url)
    }

    override fun title(item: RecentsItem): String = when (val payload = item.payload) {
        is NovelUpdatesItem -> payload.update.novelTitle
        is NovelHistoryWithRelations -> payload.title
        is RecentlyAddedNovel -> payload.title
        else -> ""
    }
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
