package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import reikai.domain.download.downloadStateOf
import reikai.domain.download.offersDownload
import reikai.domain.entry.EntryId
import reikai.domain.merge.ChapterCopyRow
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.CopyToOpen
import reikai.domain.merge.DownloadTargets
import reikai.domain.merge.MergeScope

/**
 * Every source's copy of [named], [named] first, resolved once with the row so drawing it probes only
 * these. Which of them count is [recentsRowDownloadState]'s call, by the row's lane.
 */
internal fun <T> recentsRowCopies(named: T, stitch: List<ChapterUnit>, pooled: List<T>, id: (T) -> Long): List<T> {
    val ids = MergeScope.Group.copiesOf(setOf(id(named)), stitch)
    return listOf(named) + pooled.filter { id(it) in ids && id(it) != id(named) }
}

/** A copy no stored stitch places, which [recentsRowCopies] then returns alone: its own merged chapter. */
internal fun soloUnit(chapterId: Long) = ChapterUnit(chapterId, unit = 0, copyOrder = 0)

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
 * type reads its queue by chapter id ([queued]), reports progress ([progress], null where it cannot),
 * answers whether a copy's source is installed and probes a copy on disk ([isOnDisk]) differs. The row
 * follows the copy its download fetches ([DownloadTargets], in the lane's scope) through the queue, and
 * draws no control for a chapter no installed source holds.
 */
internal fun recentsCopiesDownloadUi(
    lane: RecentsLane,
    chapterId: Long,
    copies: () -> List<ChapterCopyRow>,
    queued: (Long) -> Download.State?,
    progress: ((Long) -> Int?)?,
    isInstalled: (ChapterCopyRow) -> Boolean,
    isOnDisk: (ChapterCopyRow) -> Boolean,
): RecentsDownloadUi {
    fun targets(rows: List<ChapterCopyRow>) = DownloadTargets.of(
        lane.mergeScope,
        rows,
        rows.filter { it.copy.chapterId == chapterId },
        rows.map { it.copy },
        { it.copy.chapterId },
        isInstalled,
    )
    val state = {
        val rows = copies()
        recentsRowDownloadState(lane, chapterId, rows, targets(rows).queuedFor(chapterId, queued), isOnDisk)
    }
    return RecentsDownloadUi(
        state = state,
        progress = progress?.let { percent ->
            RecentsDownloadProgress.Live { targets(copies()).queuedFor(chapterId, percent) ?: 0 }
        } ?: RecentsDownloadProgress.Unsupported,
        offered = { targets(copies()).offersDownload(chapterId, state()) },
    )
}

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
            soloUnit(chapterId),
            ownerTitle,
            ownerSource,
            chapterName,
            scanlator,
            chapterUrl,
        ),
    )

    @Volatile
    private var missingSources: Set<String> = emptySet()

    /** Whether [copy]'s source is installed, as of the copies last loaded; a source never asked counts. */
    fun isInstalled(copy: ChapterCopyRow): Boolean = copy.ownerSource !in missingSources

    /**
     * [rows] re-emitted once the copies of its rows on a merged entry ([membership]) are in hand; which of
     * their sources [missingAmong] says are not installed follows as a [changes] tick.
     */
    fun lane(
        rows: Flow<RecentsLaneRows>,
        membership: Flow<Map<EntryId, Long>>,
        missingAmong: suspend (Set<String>) -> Set<String>,
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
        copies.transform { byId ->
            byChapter = byId
            changes.tryEmit(Unit)
            // The rows go out first: answering for a novel source can wait on the first plugin load.
            emit(lane)
            val sources = byId.values.flatten().mapTo(HashSet()) { it.ownerSource }
            // Asked only of a merged row's sources, so a feed of none loads no novel plugin to answer.
            missingSources = if (sources.isEmpty()) emptySet() else missingAmong(sources)
            changes.tryEmit(Unit)
        }
    }

    private companion object {
        const val MAX_IDS_PER_QUERY = 500
    }
}
