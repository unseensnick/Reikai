package reikai.data.library

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import reikai.domain.library.ContentType
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.merge.collapseNewChapters
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.concurrent.CopyOnWriteArrayList

/** [runLibraryUpdate] over the graph's merge stitch and the shared Updates badge, for one [ContentType]. */
@Inject
class LibraryUpdateRun(
    private val reconcileMergedChapters: ReconcileMergedChapters,
    private val mergeGroupRepository: MergeGroupRepository,
    private val mergedChapterUnitRepository: MergedChapterUnitRepository,
    private val libraryPreferences: LibraryPreferences,
) {
    suspend operator fun <E, C> invoke(
        contentType: ContentType,
        entryId: (E) -> Long,
        chapterId: (C) -> Long,
        announce: suspend (List<Pair<E, List<C>>>) -> Unit,
        queueDownloads: suspend (List<Pair<E, List<C>>>) -> Unit,
        run: suspend (UpdateRunLedger<E, C>) -> Unit,
    ) = runLibraryUpdate(
        afterPass = { reconcileMergedChapters.afterPass(it) },
        entryId = entryId,
        chapterId = chapterId,
        memberships = { mergeGroupRepository.getAllMemberships(contentType) },
        stitchOf = { mergedChapterUnitRepository.getStitch(contentType, it) },
        announce = announce,
        countArrivals = { arrivals -> libraryPreferences.newUpdatesCount.getAndSet { it + arrivals } },
        queueDownloads = queueDownloads,
        run = run,
    )
}

/** What an update run gathered, entry by entry. The manga job fills it from five coroutines at once. */
class UpdateRunLedger<E, C> {
    internal val arrivals = CopyOnWriteArrayList<Pair<E, List<C>>>()
    internal val downloads = CopyOnWriteArrayList<Pair<E, List<C>>>()

    /** [entry] gained [newChapters], of which [toDownload] may be downloaded. */
    fun arrived(entry: E, newChapters: List<C>, toDownload: List<C>) {
        if (newChapters.isNotEmpty()) arrivals += entry to newChapters
        if (toDownload.isNotEmpty()) downloads += entry to toDownload
    }
}

/**
 * One library update run, for either content type: [run] fills the ledger, the merged stitch is
 * reconciled, and the arrivals are announced, counted and downloaded one copy per merged chapter.
 * A run that ends early has written its chapters already, and they are never new again, so it
 * still counts and queues everything it fetched, uncollapsed.
 */
suspend fun <E, C> runLibraryUpdate(
    afterPass: suspend (pass: suspend () -> Unit) -> Unit,
    entryId: (E) -> Long,
    chapterId: (C) -> Long,
    memberships: suspend () -> Map<Long, Long>,
    stitchOf: suspend (groupId: Long) -> List<ChapterUnit>,
    announce: suspend (List<Pair<E, List<C>>>) -> Unit,
    countArrivals: (Int) -> Unit,
    queueDownloads: suspend (List<Pair<E, List<C>>>) -> Unit,
    run: suspend (UpdateRunLedger<E, C>) -> Unit,
) {
    val ledger = UpdateRunLedger<E, C>()
    var counted: Int? = null
    var downloads: List<Pair<E, List<C>>> = ledger.downloads
    try {
        afterPass { run(ledger) }
        if (ledger.arrivals.isNotEmpty()) {
            val arrivals = collapseNewChapters(
                ledger.arrivals.associate { (entry, chapters) -> entryId(entry) to chapters },
                memberships(),
                stitchOf,
                chapterId,
            )
            val announced = ledger.arrivals.mapNotNull { (entry, chapters) ->
                chapters.filter { chapterId(it) in arrivals.announced }.takeIf { it.isNotEmpty() }?.let { entry to it }
            }
            // Among the copies the run found eligible, not the announced ones: eligibility is per entry,
            // so the copy that may be downloaded is often not the one the stitch ranks first.
            downloads = ledger.downloads
                .flatMap { (entry, chapters) -> chapters.map { entry to it } }
                .distinctBy { (_, chapter) -> arrivals.dedupeKey(chapterId(chapter)) }
                .groupBy({ it.first }, { it.second })
                .toList()
            counted = announced.sumOf { it.second.size }
            if (announced.isNotEmpty()) announce(announced)
        }
    } finally {
        withContext(NonCancellable) {
            val count = counted ?: ledger.arrivals.sumOf { it.second.size }
            if (count > 0) countArrivals(count)
            if (downloads.isNotEmpty()) queueDownloads(downloads)
        }
    }
}
