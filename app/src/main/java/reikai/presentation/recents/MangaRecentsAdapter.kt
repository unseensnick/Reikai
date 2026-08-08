package reikai.presentation.recents

import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.presentation.manga.components.ChapterDownloadAction
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
import reikai.domain.recents.RecentlyAddedManga
import reikai.domain.recents.RecentlyAddedRepository
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
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

    override suspend fun targetChapter(item: RecentsItem): ChapterRef? {
        val mangaId = item.entryId.rawId
        val chapterId = when (val lane = item.lane) {
            is RecentsLane.Read ->
                getNextChapters.await(mangaId, lane.chapter.chapterId, onlyUnread = false).firstOrNull()?.id
            is RecentsLane.Updated -> firstUnreadInBurst(
                chapters = getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
                    .map { RecentsChapter(id = it.id, fetchedAt = it.dateFetch, read = it.read) },
                rowChapterId = lane.chapter.chapterId,
            )
            RecentsLane.Added -> getNextChapters.await(mangaId, onlyUnread = true).firstOrNull()?.id
        }
        return chapterId?.let { ChapterRef(item.entryId, it) }
    }

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
