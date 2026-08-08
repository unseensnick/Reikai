package reikai.presentation.recents

import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.recents.RecentlyAddedManga
import reikai.domain.recents.RecentlyAddedRepository
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.injectLazy

/**
 * Adapts Mihon's two live models to the neutral [RecentsProvider]. Both stay live and upstream-tracked
 * (never made to implement a Reikai interface); this maps their rows and forwards each verb to the
 * model that owns it. The chapter verbs resolve refs through the updates model's own row list, the
 * only surface a chapter is selectable from today; a ref missing from it is dropped, not guessed at.
 */
class MangaRecentsAdapter(
    private val updatesModel: UpdatesViewModel,
    private val historyModel: HistoryViewModel,
    private val surface: RecentsSurface,
) : RecentsProvider {

    // Lazy, so constructing the adapter in a composable never touches the DI container.
    private val sourcePreferences: ReikaiSourcePreferences by injectLazy()
    private val recentlyAdded: RecentlyAddedRepository by injectLazy()
    private val getNextChapters: GetNextChapters by injectLazy()
    private val getChaptersByMangaId: GetChaptersByMangaId by injectLazy()

    // Read from the preference rather than off the model, whose copy is a Compose State the engine
    // cannot collect.
    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()
    private val mergeManager: MangaMergeManager by injectLazy()
    private val mergedChapterProvider: MergedChapterProvider by injectLazy()
    private val getManga: GetManga by injectLazy()
    private val downloadManager: DownloadManager by injectLazy()

    override val contentType = ContentType.MANGA

    // A null list is this model's "no emission yet", where the updates model carries a loading flag.
    override val readLane: Flow<RecentsLaneRows> = historyModel.state.map { state ->
        RecentsLaneRows(
            items = state.list.orEmpty().mapNotNull { (it as? HistoryUiModel.Item)?.item?.toRecentsItem() },
            loaded = state.list != null,
        )
    }

    override val updatedLane: Flow<RecentsLaneRows> = updatesModel.state.map { state ->
        RecentsLaneRows(items = state.items.map { it.toRecentsItem() }, loaded = !state.isLoading)
    }

    // The only lane with no model behind it: nothing rendered a newly-added feed before this surface.
    override val addedLane: Flow<RecentsLaneRows> =
        sourcePreferences.recentsCategoryFilterFlow(surface).flatMapLatest { categories ->
            recentlyAdded.subscribeManga(
                after = addedLaneCutoff(),
                limit = ADDED_LANE_LIMIT,
                includedCategories = categories.include,
                excludedCategories = categories.exclude,
            ).map { rows -> rows.map { it.toRecentsItem() } }
        }.asLane()

    override val lastUpdated: Flow<Long> = libraryPreferences.lastUpdatedTimestamp.changes()

    override val membership: Flow<Map<EntryId, Long>> =
        mergeManager.membershipFlow(reikaiLibraryPreferences.seriesMergingEnabled, EntryId::Manga)

    /**
     * Merge-aware on all three lanes: a collapsed row stands for the whole group, so it must not reopen
     * what another of its sources already read. The group list is resolved per rendered row, which is
     * what [targetChapter] is lazy for. An unmerged entry gets its own list back, so this is the plain
     * path too.
     */
    override suspend fun targetChapter(item: RecentsItem): ChapterRef? {
        val mangaId = item.entryId.rawId
        val manga = getManga.await(mangaId)
        val group = manga?.let { mergedChapterProvider.load(it) }
        val readElsewhere = group?.readInOtherSources.orEmpty()
        val groupChapters = readingOrder(manga, group?.chapters).map { it.toRecentsChapter(readElsewhere) }
        val chapterId = when (val lane = item.lane) {
            is RecentsLane.Read -> resumeInGroup(groupChapters, lane.chapter.chapterId)
                // The stitch drops a chapter another source represents, so a recorded chapter can be
                // missing from the group list; resume it from its own source rather than nowhere.
                ?: getNextChapters.await(mangaId, lane.chapter.chapterId, onlyUnread = false).firstOrNull()?.id
            is RecentsLane.Updated -> firstUnreadInBurst(
                // The burst is one source's: fetch times do not line up across sources, so only the
                // read-elsewhere carry-over crosses the group here.
                chapters = readingOrder(manga, getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true))
                    .map { it.toRecentsChapter(readElsewhere) },
                rowChapterId = lane.chapter.chapterId,
            )
            RecentsLane.Added -> firstUnreadOf(groupChapters)
                ?: getNextChapters.await(mangaId, onlyUnread = true).firstOrNull()?.id
        }
        return chapterId?.let { ChapterRef(item.entryId, it) }
    }

    /**
     * Ascending reading order, which every shared target rule expects. A merged list can only be
     * ordered by chapter number: each source's own order is a scale of its own, and the stitch already
     * restamped it newest-first for the reader. An unmerged one takes Mihon's own comparator, the same
     * one `GetNextChapters` applies.
     */
    private fun readingOrder(manga: Manga?, chapters: List<Chapter>?): List<Chapter> = when {
        manga == null || chapters == null -> chapters.orEmpty()
        chapters.distinctBy { it.mangaId }.size > 1 -> chapters.sortedBy { it.chapterNumber }
        else -> chapters.sortedWith(getChapterSort(manga, sortDescending = false))
    }

    private fun Chapter.toRecentsChapter(readInOtherSources: Set<Long>) = RecentsChapter(
        id = id,
        fetchedAt = dateFetch,
        read = read || id in readInOtherSources,
    )

    // Each verb takes the neutral set and hands its model only its own content type's rows, so a mixed
    // selection never reaches a provider that cannot act on it.
    private fun Set<ChapterRef>.ownItems(): List<UpdatesItem> {
        val ids = filter { it.entryId is EntryId.Manga }.mapTo(HashSet()) { it.chapterId }
        if (ids.isEmpty()) return emptyList()
        return updatesModel.state.value.items.filter { it.update.chapterId in ids }
    }

    override fun markRead(chapters: Set<ChapterRef>, read: Boolean) {
        updatesModel.markUpdatesRead(chapters.ownItems(), read)
    }

    override fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) {
        updatesModel.bookmarkUpdates(chapters.ownItems(), bookmarked)
    }

    override fun download(chapters: Set<ChapterRef>) {
        updatesModel.downloadChapters(chapters.ownItems(), ChapterDownloadAction.START)
    }

    override fun deleteDownloads(chapters: Set<ChapterRef>) {
        updatesModel.deleteChapters(chapters.ownItems())
    }

    override fun removeFromHistory(entries: Set<EntryId>) {
        entries.filterIsInstance<EntryId.Manga>().forEach { historyModel.removeAllFromHistory(it.rawId) }
    }

    override fun clearHistory() {
        historyModel.removeAllHistory()
    }

    override fun title(item: RecentsItem): String = when (val payload = item.payload) {
        is UpdatesItem -> payload.update.mangaTitle
        is HistoryWithRelations -> payload.title
        is RecentlyAddedManga -> payload.title
        else -> ""
    }
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
