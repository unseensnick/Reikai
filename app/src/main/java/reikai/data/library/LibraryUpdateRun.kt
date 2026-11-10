package reikai.data.library

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import reikai.data.task.AppTask
import reikai.data.task.AppTaskProgress
import reikai.domain.download.SourceContacts
import reikai.domain.library.ContentType
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.merge.collapseNewChapters
import reikai.domain.novel.NovelMergeManager
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * [runLibraryUpdate] over the graph's merge stitch and the shared Updates badge, for one [ContentType], shown
 * on the app-state banner for as long as it runs.
 */
@Inject
class LibraryUpdateRun(
    private val reconcileMergedChapters: ReconcileMergedChapters,
    private val mangaMergeManager: MangaMergeManager,
    private val novelMergeManager: NovelMergeManager,
    private val mergedChapterUnitRepository: MergedChapterUnitRepository,
    private val libraryPreferences: LibraryPreferences,
    private val appTaskProgress: AppTaskProgress,
) {
    suspend operator fun <E, C> invoke(
        contentType: ContentType,
        entryId: (E) -> Long,
        chapterId: (C) -> Long,
        announce: suspend (List<Pair<E, List<C>>>) -> Unit,
        queueDownloads: suspend (List<Pair<E, List<C>>>) -> Unit,
        run: suspend (UpdateRunLedger<E, C>) -> Unit,
    ) {
        val task = if (contentType == ContentType.NOVELS) AppTask.NOVEL_UPDATE else AppTask.MANGA_UPDATE
        val mergeManager = if (contentType == ContentType.NOVELS) novelMergeManager else mangaMergeManager
        appTaskProgress.during(task) {
            runLibraryUpdate(
                afterPass = { reconcileMergedChapters.afterPass(it) },
                entryId = entryId,
                chapterId = chapterId,
                memberships = { mergeManager.memberships() },
                stitchOf = { mergedChapterUnitRepository.getStitch(contentType, it) },
                announce = announce,
                countArrivals = libraryPreferences::addNewUpdates,
                queueDownloads = queueDownloads,
                report = { done, total -> appTaskProgress.report(task, done, total) },
                run = run,
            )
        }
    }
}

/**
 * Adds [count] to the Updates badge. The manga, novel and gallery jobs run at once, and getAndSet is a separate
 * read and write, so the add holds the preference.
 */
fun LibraryPreferences.addNewUpdates(count: Int) {
    synchronized(newUpdatesCount) { newUpdatesCount.getAndSet { it + count } }
}

/** What an update run gathered, entry by entry. The manga job fills it from five coroutines at once. */
class UpdateRunLedger<E, C>(private val report: (done: Int, total: Int) -> Unit) {
    internal val arrivals = CopyOnWriteArrayList<Pair<E, List<C>>>()
    internal val downloads = CopyOnWriteArrayList<Pair<E, List<C>>>()

    /** [entry] gained [newChapters], of which [toDownload] may be downloaded. */
    fun arrived(entry: E, newChapters: List<C>, toDownload: List<C>) {
        if (newChapters.isNotEmpty()) arrivals += entry to newChapters
        if (toDownload.isNotEmpty()) downloads += entry to toDownload
    }

    /** [done] of the run's [total] entries are checked, for the app-state banner's percent. */
    fun progressed(done: Int, total: Int) = report(done, total)
}

/**
 * Runs [check] over [entries] for an update, either content type: up to five sources at once, the biggest
 * first, and one entry at a time within a source, so no site takes two of the run's requests together. Each
 * check marks its source reached in [contacts] as it ends, so a chapter queued after waits out the delay.
 */
suspend fun <E, S> updateBySource(
    entries: List<E>,
    sourceOf: (E) -> S,
    contacts: SourceContacts,
    check: suspend (E) -> Unit,
) {
    val semaphore = Semaphore(UPDATE_SOURCE_LIMIT)
    coroutineScope {
        entries.groupBy(sourceOf).values
            .sortedByDescending { it.size }
            .map { inSource ->
                async {
                    semaphore.withPermit {
                        inSource.forEach {
                            ensureActive()
                            // As a string, the key both downloaders pace a source by.
                            contacts.reaching(sourceOf(it).toString()) { check(it) }
                        }
                    }
                }
            }
            .awaitAll()
    }
}

private const val UPDATE_SOURCE_LIMIT = 5

/**
 * The entries a pass over [total] of them is on right now, handed to [show] as each starts and ends, since
 * [updateBySource] runs several at once. Manga's workers keep the same list by hand, in upstream's shape.
 */
class UpdateProgressList<E>(private val total: Int, private val show: suspend (List<E>, Int, Int) -> Unit) {
    private val updating = CopyOnWriteArrayList<E>()
    private val done = AtomicInteger(0)

    suspend fun around(entry: E, block: suspend () -> Unit) {
        updating += entry
        show(updating.toList(), done.get(), total)
        block()
        updating -= entry
        show(updating.toList(), done.incrementAndGet(), total)
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
    report: (done: Int, total: Int) -> Unit,
    run: suspend (UpdateRunLedger<E, C>) -> Unit,
) {
    val ledger = UpdateRunLedger<E, C>(report)
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
