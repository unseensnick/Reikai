package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import reikai.domain.download.downloadStateOf
import reikai.domain.entry.EntryId
import reikai.domain.merge.ChapterCopyRow
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.CopyToOpen
import reikai.domain.merge.MergeScope

/**
 * Every source's copy of [named], [named] first, resolved once with the row so drawing it probes only
 * these. Which of them count is [recentsRowDownloadState]'s call, by the row's lane.
 */
internal fun <T> recentsRowCopies(named: T, stitch: List<ChapterUnit>, pooled: List<T>, id: (T) -> Long): List<T> {
    val ids = MergeScope.Group.copiesOf(setOf(id(named)), stitch)
    return listOf(named) + pooled.filter { id(it) in ids && id(it) != id(named) }
}

/**
 * A recents row's download state, the one rule both adapters draw it by: the named chapter's queue
 * entry first, else whether the copy a tap opens is on disk ([CopyToOpen] in the lane's scope). An
 * Updates row opens its own source, so only its own copy counts there.
 */
internal fun <T> recentsRowDownloadState(
    lane: RecentsLane,
    named: T,
    copies: List<T>,
    stitch: List<ChapterUnit>,
    id: (T) -> Long,
    queued: Download.State?,
    isOnDisk: (T) -> Boolean,
): Download.State = downloadStateOf(queued) {
    val onDisk = copies.filter(isOnDisk).mapTo(HashSet(), id)
    CopyToOpen(lane.mergeScope, copies, stitch, id, onDisk).isOnDisk(id(named))
}

/** [recentsRowDownloadState] over stored copies, [chapterId] being the one the row names. */
internal fun recentsRowDownloadState(
    lane: RecentsLane,
    chapterId: Long,
    copies: List<ChapterCopyRow>,
    queued: Download.State?,
    isOnDisk: (ChapterCopyRow) -> Boolean,
): Download.State = recentsRowDownloadState(
    lane,
    copies.first { it.copy.chapterId == chapterId },
    copies,
    copies.map { it.copy },
    { it.copy.chapterId },
    queued,
    isOnDisk,
)

/**
 * A row's download control over [copies] of [chapterId], the shape both adapters hand out: only how a
 * type reads its queue ([queued]), reports progress and probes a copy on disk ([isOnDisk]) differs.
 */
internal fun recentsCopiesDownloadUi(
    lane: RecentsLane,
    chapterId: Long,
    copies: () -> List<ChapterCopyRow>,
    queued: () -> Download.State?,
    progress: RecentsDownloadProgress,
    isOnDisk: (ChapterCopyRow) -> Boolean,
) = RecentsDownloadUi(
    state = { recentsRowDownloadState(lane, chapterId, copies(), queued(), isOnDisk) },
    progress = progress,
)

/**
 * A read lane's grouped rows' copies, loaded with the rows ([lane]) rather than per drawn row, because
 * the Downloaded filter asks every row's state synchronously, drawn or not. [changes] ticks when the
 * copies move, which the lane itself cannot say: the same rows re-emitted are dropped as unchanged.
 */
internal class RecentsRowCopiesIndex {

    @Volatile
    private var byChapter: Map<Long, List<ChapterCopyRow>> = emptyMap()

    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * [chapterId]'s copies, or, where no group places it, its own copy alone, which the other values
     * describe. [ownerTitle] is the stored title a download folder is named from, never a custom one.
     */
    fun copiesOf(
        chapterId: Long,
        ownerTitle: String,
        ownerSource: String,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
    ): List<ChapterCopyRow> = byChapter[chapterId] ?: listOf(
        ChapterCopyRow(
            chapterId,
            ChapterUnit(chapterId, unit = 0, copyOrder = 0),
            ownerTitle,
            ownerSource,
            chapterName,
            scanlator,
            chapterUrl,
        ),
    )

    /** [rows] re-emitted once the copies of its rows on a merged entry ([membership]) are in hand. */
    fun lane(
        rows: Flow<RecentsLaneRows>,
        membership: Flow<Map<EntryId, Long>>,
        query: (List<Long>) -> Flow<Map<Long, List<ChapterCopyRow>>>,
    ): Flow<RecentsLaneRows> = combine(rows, membership, ::Pair).flatMapLatest { (lane, groups) ->
        val ids = lane.items.filter { it.entryId in groups }.mapNotNull { it.lane.chapterRef?.chapterId }
        // Chunked, since SQLite caps the number of bound variables in one IN list.
        val copies: Flow<Map<Long, List<ChapterCopyRow>>> = if (ids.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(ids.chunked(MAX_IDS_PER_QUERY).map(query)) { maps ->
                maps.fold(emptyMap<Long, List<ChapterCopyRow>>()) { a, b -> a + b }
            }
        }
        copies.map { byId ->
            byChapter = byId
            changes.tryEmit(Unit)
            lane
        }
    }

    private companion object {
        const val MAX_IDS_PER_QUERY = 500
    }
}
