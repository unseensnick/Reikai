package reikai.presentation.recents

import android.content.Context
import android.content.Intent
import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.flaggedOnAnotherSource
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.reader.ChapterProgress
import reikai.domain.recents.RECENTS_FEED_LIMIT
import reikai.domain.recents.RecentlyAddedNovel
import reikai.domain.recents.RecentlyAddedRepository
import reikai.domain.recents.RecentsUnreadRepository
import reikai.domain.recents.recentsFeedCutoff
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.toDownloadState
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.decideAdd
import reikai.presentation.history.NovelHistoryViewModel
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.updates.NovelUpdatesItem
import reikai.presentation.updates.NovelUpdatesViewModel

/**
 * The novel twin of [MangaRecentsAdapter], over Reikai's two novel models. Like the manga pair, both
 * stay live behind this adapter as the feeds, wrapped the same way so the seam is symmetric.
 */
@AssistedInject
class NovelRecentsAdapter(
    // Assisted: the models belong to the surface that is composing, so only the call site has them.
    @Assisted private val updatesModel: NovelUpdatesViewModel?,
    @Assisted private val historyModel: NovelHistoryViewModel?,
    @Assisted private val surface: RecentsSurface,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val recentlyAdded: RecentlyAddedRepository,
    private val getCustomNovelInfo: GetCustomNovelInfo,
    private val recentsUnread: RecentsUnreadRepository,
    private val getNextNovelChapter: GetNextNovelChapter,
    private val novelPreferences: NovelPreferences,
    private val novelRepository: NovelRepository,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val mergeManager: NovelMergeManager,
    private val novelLibraryAdder: NovelLibraryAdder,
    // Providers, so building the adapter still does not build the download manager: constructing it
    // restores the persisted queue and can start the download worker. Both are only read when a row
    // renders its download badge, which is where the previous lazy delegates built them too.
    private val novelDownloadManagerProvider: () -> NovelDownloadManager,
    private val novelDownloadCacheProvider: () -> NovelDownloadCache,
    private val application: Context,
    override val chapterActions: NovelRecentsChapterActions,
) : RecentsProvider {

    /** One entry point per surface, the twin of [MangaRecentsAdapter]'s. */
    @AssistedFactory
    interface Factory {
        fun create(
            updatesModel: NovelUpdatesViewModel?,
            historyModel: NovelHistoryViewModel?,
            surface: RecentsSurface,
        ): NovelRecentsAdapter

        fun forUpdates(updatesModel: NovelUpdatesViewModel) =
            create(updatesModel, historyModel = null, surface = RecentsSurface.UPDATES)

        fun forHistory(historyModel: NovelHistoryViewModel) =
            create(updatesModel = null, historyModel = historyModel, surface = RecentsSurface.HISTORY)

        fun forRecents(updatesModel: NovelUpdatesViewModel, historyModel: NovelHistoryViewModel) =
            create(updatesModel, historyModel, surface = RecentsSurface.RECENTS)
    }

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
            combine(
                recentlyAdded.subscribeNovels(
                    after = recentsFeedCutoff(),
                    limit = RECENTS_FEED_LIMIT,
                    includedCategories = categories.include,
                    excludedCategories = categories.exclude,
                ),
                getCustomNovelInfo.subscribeAll(),
            ) { rows, customInfo ->
                val overlay = customInfo.associateBy { it.novelId }
                rows.map { it.withCustomInfo(overlay[it.novelId]).toRecentsItem() }
            }
        }.asLane()

    override val unreadEntries: Flow<Set<EntryId>> =
        reikaiLibraryPreferences.seriesMergingEnabled.changes()
            .flatMapLatest { recentsUnread.subscribeNovelIdsWithUnread(mergingEnabled = it) }
            .map { ids -> ids.mapTo(HashSet(), EntryId::Novel) }

    override val chapterWrites: Flow<Unit> = recentsUnread.novelChapterWrites()

    override val lastUpdated: Flow<Long> = novelPreferences.novelLibraryUpdateLastTimestamp().changes()

    override val updating: Flow<Boolean> = NovelUpdateJob.isRunningFlow(application)

    override val membership: Flow<Map<EntryId, Long>> =
        mergeManager.membershipFlow(reikaiLibraryPreferences.seriesMergingEnabled, EntryId::Novel)

    // Built on collection, which the engine does only while its chip shows novels, so a surface drawing
    // no novel row never builds the download manager. The queue carries each download's state.
    override val downloadChanges: Flow<Unit> = flow {
        emitAll(merge(novelDownloadCacheProvider().changes, novelDownloadManagerProvider().queueState.map { }))
    }

    // The novel engine reports no byte progress, matching the `Unsupported` its download states declare.
    override val progressChanges: Flow<Unit> = emptyFlow()

    override suspend fun targetChapter(item: RecentsItem): ChapterRef? =
        resolveTarget(item)?.let { ChapterRef(item.entryId, it.chapterId) }

    override suspend fun targetRow(item: RecentsItem): RecentsTargetRow? {
        val resolved = resolveTarget(item) ?: return null
        val chapter = resolved.chapters[resolved.chapterId] ?: return null
        // Not necessarily this row's novel: a merged row resolves across the group, and the download
        // lookup is keyed by the owner's stored title and source.
        val owner = novelRepository.getById(chapter.novelId) ?: return null
        return RecentsTargetRow(
            ref = ChapterRef(EntryId.Novel(owner.id), chapter.id),
            chapter = item.lane.chapterLabel(chapter.name, chapter.chapterNumber),
            state = chapterState(
                read = chapter.read || chapter.id in resolved.readElsewhere,
                bookmark = chapter.bookmark || chapter.id in resolved.bookmarkedElsewhere,
                progress = ChapterProgress.Percent(chapter.lastTextProgress),
            ),
            download = chapterDownloadUi(
                chapterId = chapter.id,
                source = owner.source,
                storedTitle = owner.title,
                chapterName = chapter.name,
                chapterUrl = chapter.url,
            ),
        )
    }

    /** The twin of the manga adapter's, holding this type's chapters and the group's flags. */
    private class TargetResolution(
        val chapterId: Long,
        val chapters: Map<Long, NovelChapter>,
        val readElsewhere: Set<Long> = emptySet(),
        val bookmarkedElsewhere: Set<Long> = emptySet(),
    )

    /** Merge-aware on all three lanes, the twin of [MangaRecentsAdapter]'s. */
    private suspend fun resolveTarget(item: RecentsItem): TargetResolution? {
        val novelId = item.entryId.rawId
        // Already ascending reading order, which is the contract every rule below reads under.
        val group = getNextNovelChapter.groupChapters(novelId)
        // Every chapter a rule below could name, so the id it returns can be projected back into a
        // row: the stitch drops the copies another source stands in for, so the two lists differ.
        val chapters = group.chapters.associateByTo(mutableMapOf()) { it.id }

        // In the novel's own reading order, which is what every rule below reads under; the stored
        // rows arrive in whatever order the table hands them over.
        suspend fun ownSource(): List<NovelChapter> =
            getNextNovelChapter.ownSourceChapters(novelId).onEach { chapters[it.id] = it }
        val isHidden = getNextNovelChapter.hiddenAmong(group.pooledChapters)
        fun List<NovelChapter>.forRules() =
            recentsChapters(this, group.pooledChapters, group.stitch, {
                it.id
            }, { it.dateFetch }, { it.read }, isHidden)

        val chapterId = when (val lane = item.lane) {
            is RecentsLane.Read -> resumeTarget(group.chapters.forRules(), lane.chapter.chapterId) {
                ownSource().forRules()
            }
            is RecentsLane.Updated -> firstUnreadInBurst(
                // The burst stays within one source; only the read-elsewhere carry-over crosses the group.
                chapters = ownSource().forRules(),
                rowChapterId = lane.chapter.chapterId,
            )
            RecentsLane.Added -> addedTarget(group.chapters.forRules()) { ownSource().forRules() }
        } ?: return null
        // Over both lists, so a row naming a copy the stitch dropped says what the group says of it.
        val named = chapters.values.toList()
        return TargetResolution(
            chapterId = chapterId,
            chapters = chapters,
            readElsewhere = flaggedOnAnotherSource(group.pooledChapters, named, group.stitch, { it.id }, { it.read }),
            bookmarkedElsewhere = flaggedOnAnotherSource(
                group.pooledChapters,
                named,
                group.stitch,
                { it.id },
                { it.bookmark },
            ),
        )
    }

    override suspend fun latestRead(): RecentsItem? = historyModel?.getLast()?.toRecentsItem()

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
                            it.toDuplicateCard(found.sourceLabels),
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

    override suspend fun applyAddCategories(entry: EntryId, categoryIds: List<Long>, joinGroup: List<EntryId>) {
        val novel = novelOf(entry) ?: return
        if (joinGroup.isEmpty()) {
            novelLibraryAdder.confirmAddCategories(novel.id, categoryIds)
        } else {
            novelLibraryAdder.confirmGroupCategories(novel.id, joinGroup.map { it.rawId }, categoryIds)
        }
    }

    override suspend fun addToGroup(entry: EntryId, duplicates: List<EntryId>): AddFavoriteResult {
        val novel = novelOf(entry) ?: return AddFavoriteResult.Failed
        return novelLibraryAdder.addToExistingGroup(novel.id, duplicates.map { it.rawId })
    }

    override suspend fun clearHistory(): Boolean = historyModel?.removeAllHistory() == true

    // Straight to the job, the twin of the manga side and for the same two reasons.
    override fun refresh(): Boolean = NovelUpdateJob.startNow(application.workManager)

    override suspend fun detailsScreen(entry: EntryId): Screen? {
        val novelId = (entry as? EntryId.Novel)?.rawId ?: return null
        val novel = novelRepository.getById(novelId) ?: return null
        return NovelScreen(novel.source, novel.url)
    }

    // No lookup, unlike detailsScreen: the novel reader is keyed by id, not by source and url.
    override fun open(item: RecentsItem, chapter: ChapterRef): Intent = ReaderActivity.newNovelIntent(
        application,
        item.entryId.rawId,
        chapter.chapterId,
        sourceScoped = item.lane.sourceScoped,
    )

    override fun rowUi(item: RecentsItem): RecentsRowUi = novelRowUi(item)

    override fun downloadUi(item: RecentsItem): RecentsDownloadUi? = when (val payload = item.payload) {
        is NovelHistoryWithRelations -> historyDownloadUi(payload)
        else -> novelDownloadUi(item)
    }

    private fun historyDownloadUi(payload: NovelHistoryWithRelations) = chapterDownloadUi(
        chapterId = payload.chapterId,
        source = payload.source,
        // The stored title, never the displayed one: a download folder is named from the former and
        // the history row carries the user's custom title in the latter.
        storedTitle = payload.storedTitle,
        chapterName = payload.chapterName,
        chapterUrl = payload.chapterUrl,
    )

    /**
     * Twin of the manga adapter's: the queue first, then the on-disk index, resolved on call. One
     * definition for this type, whichever chapter is asking.
     */
    private fun chapterDownloadUi(
        chapterId: Long,
        source: String,
        storedTitle: String,
        chapterName: String,
        chapterUrl: String,
    ) = RecentsDownloadUi(
        state = {
            val queued = novelDownloadManagerProvider().queueState.value.find { it.chapterId == chapterId }
            when {
                queued != null -> queued.state.toDownloadState()
                novelDownloadCacheProvider().isChapterDownloaded(
                    source,
                    storedTitle,
                    chapterName,
                    chapterUrl,
                ) -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }
        },
        // Same declaration the updated lane makes: the novel downloader tracks no per-chapter progress,
        // and a zero would read as a download that has genuinely stalled.
        progress = RecentsDownloadProgress.Unsupported,
    )
}

/**
 * The novel row stores a state rather than a provider, and cannot answer byte progress at all, since
 * the novel downloader tracks none per chapter. It declares that rather than reporting a zero the
 * renderer could not tell from a download that has genuinely started.
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
        chapter = RecentsChapterUi.Named(payload.update.chapterName),
        state = chapterState(
            read = payload.update.read,
            bookmark = payload.update.bookmark,
            progress = ChapterProgress.Percent(payload.update.lastTextProgress),
        ),
    )
    is NovelHistoryWithRelations -> RecentsRowUi(
        cover = payload.coverData,
        title = payload.title,
        // novelHistoryView is not favorite-gated: a read entry may never have been added.
        isFavorite = payload.coverData.isNovelFavorite,
        chapter = RecentsChapterUi.Number(payload.chapterNumber),
        state = chapterState(
            read = payload.read,
            bookmark = payload.bookmark,
            progress = ChapterProgress.Percent(payload.lastTextProgress),
        ),
    )
    is RecentlyAddedNovel -> RecentsRowUi(
        cover = payload.coverData,
        title = payload.title,
        isFavorite = true,
        chapter = null,
        state = null,
    )
    else -> EMPTY_RECENTS_ROW
}

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

/** The novel twin of the manga overlay, which [RecentsMappingTest] pins over both. */
internal fun RecentlyAddedNovel.withCustomInfo(custom: CustomNovelInfo?): RecentlyAddedNovel {
    if (custom == null) return this
    return copy(
        title = custom.title ?: title,
        coverData = coverData.copy(url = custom.thumbnailUrl ?: coverData.url),
    )
}

internal fun RecentlyAddedNovel.toRecentsItem(): RecentsItem = RecentsItem(
    entryId = EntryId.Novel(novelId),
    timestamp = dateAdded,
    lane = RecentsLane.Added,
    payload = this,
)
