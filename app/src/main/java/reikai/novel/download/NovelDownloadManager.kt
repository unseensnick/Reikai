package reikai.novel.download

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.notificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import reikai.data.notification.isHiddenAdult
import reikai.domain.download.DownloadPacing
import reikai.domain.download.DownloadRetry
import reikai.domain.download.MAX_PARALLEL_SOURCES
import reikai.domain.download.SeriesCompletions
import reikai.domain.download.deletableDownloads
import reikai.domain.download.downloadNetworkIssue
import reikai.domain.download.hasRoomToDownload
import reikai.domain.download.movesDownloadFolder
import reikai.domain.download.parallelSourcesFor
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.manga.AdultContentChecker
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.downloadedChapterIds
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.withCustomInfo
import reikai.domain.novel.ownersOf
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.SourceTitlesRepository
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.EmptyChapterException
import reikai.novel.source.NovelSourceManager
import reikai.presentation.download.inOrderOf
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * App-scoped, text-only download engine for light-novel chapters. One queue, drained by [runQueue]'s lanes with
 * sources side by side up to the parallel limit, writes a self-contained HTML file per chapter under a stable-name
 * path ([NovelDownloadProvider]), and "downloaded" is decided from a disk scan
 * ([NovelDownloadCache]), so downloads survive reinstall, restore and storage moves. Draining runs inside
 * [NovelDownloadWorker], a foreground worker, so downloads survive backgrounding. Each chapter's source is resolved
 * from its `novelId`, so the entry points work from a cold background process.
 */
@Inject
@SingleIn(AppScope::class)
class NovelDownloadManager(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val provider: NovelDownloadProvider,
    private val cache: NovelDownloadCache,
    private val chapterRepo: NovelChapterRepository,
    private val novelRepo: NovelRepository,
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val downloadPreferences: DownloadPreferences,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val novelPreferences: NovelPreferences,
    private val saver: NovelChapterSaver,
    private val securityPreferences: SecurityPreferences,
    private val adultChecker: AdultContentChecker,
    private val sourceTitles: SourceTitlesRepository,
    private val getEntryCustomInfo: GetEntryCustomInfo,
) {

    private val store = NovelDownloadStore(context, chapterRepo)

    /**
     * Held for every write to [store]. Each write follows the live queue's change, and a rewrite reads the
     * live queue under it, so no write can put back a row the queue already dropped.
     */
    private val storeLock = Any()

    private val _queueState = MutableStateFlow<List<NovelDownload>>(emptyList())
    val queueState: StateFlow<List<NovelDownload>> = _queueState.asStateFlow()

    /** Each source a lane holds and the novel it is on, held across the between-chapter pacing delay. */
    private val lanes = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The novels being downloaded, held across the pacing delay so a card doesn't flicker to "Queued". */
    val downloadingNovelIds: Flow<Set<Long>> = lanes.map { it.values.toSet() }.distinctUntilChanged()

    /** True while the drain worker is running (drives the queue FAB's Pause/Resume); false when the
     *  user paused or the queue is idle. Mirrors the manga DownloadManager.isDownloaderRunning, pinned by
     *  DownloadQueueProvider.isRunning, which both queue adapters answer from their worker. */
    val isDownloaderRunning: Flow<Boolean> get() = NovelDownloadWorker.isRunningFlow(context)

    /** Chapters finished per novel while it stayed queued, read by the download queue's cards. */
    val completions = SeriesCompletions()

    /**
     * Held for a whole [runQueue], so one drain runs at a time. A second call waits rather than
     * returning: a paused drain can still be unwinding a blocking save when Resume starts the next
     * worker, and a caller that returned then would end that worker with the queue left idle.
     */
    private val drainLock = Mutex()

    /**
     * Held across a chapter's write, which first checks the row is still queued and reads its names afresh,
     * and across each change that moves or deletes files. A chapter dropped or renamed while its fetch ran is
     * then never written under a folder or name that has gone, which Mihon gets by cancelling the removed
     * download's job. Never held across a fetch, or one slow site would stop every source's writes.
     */
    private val saveLock = Mutex()

    /** Held while a lane picks its next chapter, so two lanes can never take one source. */
    private val claimLock = Mutex()

    /** Held through a wait for the network, so one lane reports it and the others queue behind that one. */
    private val networkWait = Mutex()

    /** Which source each queued novel reads from, filled as [claimNext] meets them. Under [claimLock]. */
    private val novelSources = HashMap<Long, String>()

    /** Per-source pacing delay (ms), adapted by [runQueue]: halved on success, doubled on failure. */
    private val sourceDelays = ConcurrentHashMap<String, Long>()

    /**
     * Loads the saved queue on launch, off the main thread, behind anything queued meanwhile, as Mihon's
     * Downloader appends its restored queue. A row no longer saved by the time it lands (cancelled or
     * cleared while the restore read the database) stays out. Nothing starts here, as in Mihon: a worker
     * the system was running is rescheduled by WorkManager, and any other queue waits for Resume. It is
     * also why the manager's consumers take a provider: building it reads the database.
     */
    private val restoreJob = scope.launch {
        val restored = store.restore()
        synchronized(storeLock) {
            val saved = store.persisted().mapTo(HashSet()) { it.chapterId }
            _queueState.update { current ->
                val live = current.mapTo(HashSet()) { it.chapterId }
                current + restored.filter { it.chapterId in saved && it.chapterId !in live }
            }
        }
    }

    /** True once the launch restore is in [queueState], which it updates before it completes. */
    val isQueueRestored: Boolean get() = restoreJob.isCompleted

    /** How many chapters of [novel] are on disk, from the same cache the reader consults. */
    fun getDownloadCount(novel: Novel): Int = cache.getDownloadCount(novel)

    /** The downloaded HTML for a chapter, or null when it isn't downloaded. No host involvement. */
    fun getChapterText(novel: Novel, chapter: NovelChapter): String? =
        provider.readChapter(novel, chapter)

    /** Queue [chapters], dropping any already on disk, as Mihon's Downloader.queueChapters does, so no
     *  caller can fetch a downloaded chapter again. Suspends to look up each chapter's own novel. */
    suspend fun downloadChapters(chapters: List<NovelChapter>) {
        val onDisk = cache.downloadedChapterIds(chapters, novelRepo.ownersOf(chapters))
        val targets = chapters
            .filterNot { it.id in onDisk }
            .map { ch -> NovelDownload(novelId = ch.novelId, chapterId = ch.id, url = ch.url) }
        if (targets.isEmpty()) return
        // Only what was not queued is saved, as Mihon's Downloader stores only its toAdd: saving a queued row
        // again gives it a new order, so a restart would move it last. A failed row is already saved.
        var added = emptyList<NovelDownload>()
        _queueState.update { current ->
            val byId = current.associateByTo(LinkedHashMap()) { it.chapterId }
            added = targets.filter { it.chapterId !in byId }
            targets.forEach { t ->
                val existing = byId[t.chapterId]
                // Re-queue an errored entry, add a brand-new one, leave a queued/active one alone.
                if (existing == null || existing.state == NovelDownload.State.ERROR) {
                    byId[t.chapterId] = t
                }
            }
            byId.values.toList()
        }
        synchronized(storeLock) { store.addAll(added) }
        // Adding downloads implies wanting them, so clear any user pause and (re)start the drain.
        sourcePreferences.novelDownloadsPaused.set(false)
        dismissPausedNotification()
        NovelDownloadWorker.start(context)
    }

    /** Stop the running job and clear the entire pending queue. Downloaded chapters are kept; only
     *  what's still queued is discarded. */
    fun cancelAllDownloads() {
        NovelDownloadWorker.stop(context)
        sourcePreferences.novelDownloadsPaused.set(false)
        synchronized(storeLock) {
            _queueState.value = emptyList()
            store.clear()
        }
        completions.clear()
        dismissPausedNotification()
    }

    private fun dismissPausedNotification() {
        context.notificationManager.cancel(Notifications.ID_NOVEL_DOWNLOADER_PAUSED)
    }

    /** User pause: stop the drain without clearing the queue. The flag only tells the worker's last
     *  notification to offer Resume, since nothing restarts a queue on launch anyway. The worker is
     *  cancelled; an in-flight chapter goes back to QUEUE at the next drain start (see [runQueue]). */
    fun pauseDownloads() {
        sourcePreferences.novelDownloadsPaused.set(true)
        NovelDownloadWorker.stop(context)
    }

    val isPausedByUser: Boolean get() = sourcePreferences.novelDownloadsPaused.get()

    /** User resume: clear the pause and restart the drain. */
    fun startDownloads() {
        sourcePreferences.novelDownloadsPaused.set(false)
        dismissPausedNotification()
        NovelDownloadWorker.start(context)
    }

    /** Drop chapters from the pending queue without deleting any downloaded file/flag (the chip's
     *  CANCEL action). Sibling of [deleteChapters], which also removes the on-disk file. */
    fun cancelDownloads(chapterIds: List<Long>) {
        val ids = chapterIds.toSet()
        if (ids.isEmpty()) return
        _queueState.update { q -> q.filter { it.chapterId !in ids } }
        afterDequeue()
        scope.launch { removeSaved(ids) }
    }

    /** Bump a queued chapter to the front so it downloads next, retrying it if it failed, and persist
     *  the order, as manga's startDownloadNow does. No-op if it isn't queued. */
    fun startDownloadNow(chapterId: Long) {
        _queueState.update { q ->
            val target = q.find { it.chapterId == chapterId } ?: return@update q
            val front = if (target.state ==
                NovelDownload.State.ERROR
            ) {
                target.copy(state = NovelDownload.State.QUEUE)
            } else {
                target
            }
            listOf(front) + q.filter { it.chapterId != chapterId }
        }
        persistQueue()
        startDownloads()
    }

    /**
     * Puts the pending queue in [downloads]' order (drag-to-reorder or sort from the queue screen) and
     * persists it, so a cold restart drains in the new order. [downloads] may be an older copy, so only
     * its order is taken: a chapter that left the queue since stays out and one queued since is kept,
     * last. The drain re-reads the queue each step, so the in-flight chapter is left alone.
     */
    fun reorderQueue(downloads: List<NovelDownload>) {
        _queueState.update { q -> q.inOrderOf(downloads.map { it.chapterId }) { it.chapterId } }
        persistQueue()
    }

    /**
     * Rewrites the saved queue from the live one as it is at write time, once the launch restore has
     * landed: a rewrite before it would delete the saved rows it has not read yet.
     */
    private fun persistQueue() {
        scope.launch {
            restoreJob.join()
            synchronized(storeLock) { store.replaceAll(_queueState.value) }
        }
    }

    /** Call after the live queue dropped [chapterIds], so a rewrite cannot save them again. */
    private fun removeSaved(chapterIds: Collection<Long>) {
        synchronized(storeLock) { store.removeAll(chapterIds) }
    }

    /** Relocate a downloaded chapter's file after a source re-title, keeping the disk index in sync.
     *  Called from the chapter sync; no-op when the chapter isn't downloaded. */
    suspend fun renameChapter(novel: Novel, oldChapter: NovelChapter, newChapter: NovelChapter) = saveLock.withLock {
        val renamed = withIOContext { provider.renameChapter(novel, oldChapter, newChapter) }
        if (renamed) cache.renameChapter(novel, oldChapter, newChapter)
    }

    /**
     * Stores [newTitle] through [commit], then moves the novel's downloads to its folder, the twin of
     * `DownloadManager.renameManga`, pinned by [movesDownloadFolder]. Both happen under the save lock: a chapter
     * saved between them takes the new title and makes its folder first, stranding the old one. As there, the
     * novel's queued chapters are dropped first, so none is written into the folder being moved. Answers what
     * [commit] did; the folder moves only once it stored the title.
     */
    suspend fun renameNovel(novel: Novel, newTitle: String, commit: suspend () -> Boolean): Boolean =
        saveLock.withLock {
            if (!commit()) return@withLock false
            moveNovelFolder(novel, newTitle)
            true
        }

    // Under the save lock, which the folder is looked up under too: the chapter being saved may be creating it.
    private suspend fun moveNovelFolder(novel: Novel, newTitle: String) {
        val dir = provider.findNovelDir(novel) ?: return
        val newName = provider.novelDirName(newTitle)
        if (dir.name == newName) return
        val otherFolders = sourceTitles.otherNovelTitles(novel.source, novel.id).map { provider.novelDirName(it) }
        if (!movesDownloadFolder(dir, newName, otherFolders)) return
        cancelDownloads(_queueState.value.filter { it.novelId == novel.id }.map { it.chapterId })
        if (withIOContext { provider.renameNovel(novel, newTitle) }) {
            cache.renameNovel(novel, newTitle)
        } else {
            logcat(LogPriority.ERROR) { "Failed to rename novel download folder: ${dir.name}" }
        }
    }

    /**
     * The Delete a user asked for: [chapters]' downloads go, but for what [deletableDownloads] keeps, as
     * manga's `DownloadManager.deleteChapters` does. Automatic removal filters through
     * NovelRemovableDownloads before it calls this, which is where the kept categories are asked.
     */
    fun deleteChapters(chapters: List<NovelChapter>) {
        if (chapters.isEmpty()) return
        scope.launch {
            val deletable = deletableDownloads(
                chapters,
                allowBookmarked = novelPreferences.removeBookmarkedChapters().get(),
                isBookmarked = NovelChapter::bookmark,
            )
            if (deletable.isEmpty()) return@launch
            saveLock.withLock {
                dequeueChapters(deletable)
                deleteChapterFiles(deletable)
            }
        }
    }

    /** The novel's download directory, for the details overflow's Open folder; null until something
     *  is downloaded. The novel side of the lookup [reikai.presentation.details.openDownloadFolder] opens
     *  for both types, which owns the nothing-downloaded case. */
    suspend fun findNovelDir(novel: Novel) = withIOContext { provider.findNovelDir(novel) }

    /**
     * Drop the whole novel: everything it has queued, then its folder. The manga twin of this is
     * [eu.kanade.tachiyomi.data.download.DownloadManager.deleteManga], pinned by EmptiedPausedQueueConformanceTest.
     *
     * Chapter-by-chapter deletion cannot do this job. It only reaches what the disk cache already
     * reports, and a queued chapter is not downloaded yet, so the worker would keep fetching into a
     * novel migrated away from.
     */
    suspend fun awaitDeleteNovel(novel: Novel): Unit = saveLock.withLock {
        val queued = _queueState.value.filter { it.novelId == novel.id }.map { it.chapterId }
        _queueState.update { q -> q.filterNot { it.novelId == novel.id } }
        afterDequeue()
        withIOContext {
            removeSaved(queued)
            provider.deleteNovel(novel)
        }
        cache.removeNovel(novel)
    }

    private fun dequeueChapters(chapters: List<NovelChapter>) {
        val ids = chapters.map { it.id }.toSet()
        _queueState.update { q -> q.filter { it.chapterId !in ids } }
        afterDequeue()
    }

    /** After the user removed chapters: a paused queue emptied one series at a time has nothing left to resume. */
    private fun afterDequeue() {
        retainQueuedCompletions()
        if (_queueState.value.isEmpty()) dismissPausedNotification()
    }

    private fun retainQueuedCompletions() {
        completions.retainOnly(_queueState.value.mapTo(HashSet()) { it.novelId })
    }

    private suspend fun deleteChapterFiles(chapters: List<NovelChapter>) {
        val novelsById = novelRepo.ownersOf(chapters)
        removeSaved(chapters.map { it.id })
        chapters.groupBy { it.novelId }.forEach { (novelId, owned) ->
            val novel = novelsById[novelId] ?: return@forEach
            provider.deleteChapters(novel, owned)
            cache.removeChapters(novel, owned)
        }
        // A novel left with nothing downloaded loses its folder, as a manga does.
        novelsById.values.filter(provider::isNovelDirEmpty).forEach { novel ->
            provider.deleteNovel(novel)
            cache.removeNovel(novel)
        }
    }

    /**
     * Drain the queue until empty, up to the parallel source limit of sources at once, each one chapter at a time
     * and paced, so a slow site holds up only itself. Called by [NovelDownloadWorker]; the worker stays foreground
     * for the duration. Waits for the launch restore first, so a worker WorkManager reschedules after a restart
     * sees the saved queue. [onProgress] reports the chapter being downloaded, or why the drain is paused.
     */
    suspend fun runQueue(
        onProgress: suspend (NovelDownloadProgress) -> Unit,
        onError: (novel: Novel?, chapterName: String?, error: String?, isAdult: Boolean) -> Unit,
    ) = drainLock.withLock {
        // Off WorkManager's default dispatcher, since an image fetch and a file write block.
        withIOContext {
            try {
                installer.ensureLoaded()
                restoreJob.join()
                // Everything still in the queue goes back to QUEUE, matching manga's Downloader.start. A
                // finished download leaves the queue, so what is left is either DOWNLOADING from a drain
                // that was cancelled (a user pause, a crash, a force-kill) or ERROR, which is exactly what
                // Resume is for. The loop below only picks QUEUE, so an ERROR row left as it is would never run.
                _queueState.update { q -> q.map { it.copy(state = NovelDownload.State.QUEUE) } }
                val done = AtomicInteger(0)
                val reporting = Mutex()
                val report: suspend (NovelDownloadProgress) -> Unit = { reporting.withLock { onProgress(it) } }
                coroutineScope {
                    repeat(MAX_PARALLEL_SOURCES) {
                        launch {
                            while (true) {
                                val next = claimNext(report) ?: break
                                try {
                                    download(next, done, report, onError)
                                } finally {
                                    lanes.update { it - next.source }
                                }
                            }
                        }
                    }
                }
            } finally {
                novelSources.clear()
            }
        }
    }

    private class Claimed(val download: NovelDownload, val source: String)

    /**
     * The first queued chapter whose source no lane holds, with that source taken, or null once the queue has
     * nothing queued and no lane running. A lane with nothing it may take waits, so a chapter queued mid-drain or
     * a limit raised mid-drain reaches a free lane. No network, or Wi-Fi only off Wi-Fi, pauses the drain instead
     * of failing chapters, with the worker kept foreground as manga's is.
     */
    private suspend fun claimNext(report: suspend (NovelDownloadProgress) -> Unit): Claimed? {
        while (true) {
            if (hasQueued() && networkIssue() != null) {
                networkWait.withLock { awaitNetwork(report) }
                continue
            }
            val limit = downloadPreferences.parallelSourceLimit
            val seen = Triple(_queueState.value, lanes.value, limit.get())
            val claimed = claimLock.withLock {
                if (lanes.value.isEmpty() && !hasQueued()) return null
                if (lanes.value.size >= parallelSourcesFor(seen.third)) return@withLock null

                val next = _queueState.value
                    .asSequence()
                    .filter { it.state == NovelDownload.State.QUEUE }
                    .firstOrNull { sourceOf(it.novelId) !in lanes.value }
                    ?: return@withLock null
                val source = sourceOf(next.novelId)
                lanes.update { it + (source to next.novelId) }
                setState(next.chapterId, NovelDownload.State.DOWNLOADING)
                Claimed(next, source)
            }
            if (claimed != null) return claimed
            combine(_queueState, lanes, limit.changes().onStart { emit(limit.get()) }, ::Triple).first { it != seen }
        }
    }

    // A novel gone from the database still needs a lane: its row fails there, and "" is no real source.
    private suspend fun sourceOf(novelId: Long): String =
        novelSources.getOrPut(novelId) { novelRepo.getById(novelId)?.source.orEmpty() }

    private suspend fun download(
        claimed: Claimed,
        done: AtomicInteger,
        onProgress: suspend (NovelDownloadProgress) -> Unit,
        onError: (novel: Novel?, chapterName: String?, error: String?, isAdult: Boolean) -> Unit,
    ) {
        val next = claimed.download
        val novel = novelRepo.getById(next.novelId)
        // What the notices name it by; the chapter is still saved under the source title.
        val shown = novel?.withCustomInfo(getEntryCustomInfo.await(EntryId.Novel(novel.id)))
        val chapter = chapterRepo.getById(next.chapterId)
        val total = pendingTotal(done.get())
        val isAdult = isHiddenAdult(
            novel,
            securityPreferences.hideAdultNotificationContent.get(),
            Novel::id,
            adultChecker::adultNovelIdsAmong,
        )
        // Checked before the fetch, so a full disk costs no source request and no retries.
        if (!hasRoomToDownload(provider.availableSpace())) {
            val reason = context.stringResource(MR.strings.download_insufficient_space)
            setState(next.chapterId, NovelDownload.State.ERROR, reason)
            onError(shown, chapter?.name, reason, isAdult)
            return
        }
        onProgress(NovelDownloadProgress.Downloading(done.get(), total, shown?.title.orEmpty(), isAdult, shown))
        // Try a few times before giving up so a transient network blip or a momentarily
        // rate-limited source doesn't kill the chapter on the first stumble. The manga Downloader
        // retries each page image on this schedule; a chapter's text is one fetch, so the unit
        // here is the chapter. Backoff is separate from the cross-chapter pacing below.
        var ok = false
        var attempt = 0
        var lastError: Throwable? = null
        var connectionLost = false
        var dropped = false
        // The least delay the source declares binds its retries as well as its pacing.
        val minimumMs = novel?.let { sourceManager.get(it.source) }?.minimumRequestDelayMs ?: 0L
        while (true) {
            // A pause cancels the worker mid-attempt, and the last attempt has no delay to rethrow it.
            ok = runCatchingCancellable {
                val source = novel?.let { sourceManager.get(it.source) }
                    ?: return@runCatchingCancellable false
                if (chapter == null) return@runCatchingCancellable false
                val html = source.parseChapter(next.url).ifBlank { throw EmptyChapterException() }
                val selfContained = saver.prepare(source, html)
                saveLock.withLock {
                    // Cancelled, or its novel deleted or renamed, while the fetch ran: nothing to write
                    dropped = _queueState.value.none { it.chapterId == next.chapterId }
                    // Read again, since a sync may have renamed either during the fetch and the file takes their names.
                    // A name another chapter's download holds counts as downloaded, as manga's downloader counts it
                    dropped || saver.write(
                        novelRepo.getById(novel.id) ?: novel,
                        chapterRepo.getById(chapter.id) ?: chapter,
                        selfContained,
                    ) != NovelChapterSaver.SaveResult.FAILED
                }
            }.getOrElse {
                lastError = it
                logcat(LogPriority.ERROR, it) {
                    "Novel chapter download attempt ${attempt + 1} failed: chapter=${next.chapterId}"
                }
                false
            }
            if (ok) break
            // A drop mid-download is a pause, not a failure: stop retrying and let claimNext's
            // network check wait it out, instead of spending retries (off Wi-Fi too) and erroring it.
            if (networkIssue() != null) {
                connectionLost = true
                break
            }
            // An empty answer is the source's, not a blip, so trying again gets the same.
            if (attempt >= DownloadRetry.MAX_RETRIES || lastError is EmptyChapterException) break
            delay(maxOf(DownloadRetry.delayBefore(attempt).inWholeMilliseconds, minimumMs))
            attempt++
        }
        if (connectionLost) {
            // Requeue so it's re-picked when the connection returns (not left DOWNLOADING or ERROR).
            setState(next.chapterId, NovelDownload.State.QUEUE)
            return
        }
        // A dropped row is already out of the queue and the store, and neither finished nor failed.
        if (ok && !dropped) {
            completions.record(next.novelId)
            _queueState.update { q -> q.filter { it.chapterId != next.chapterId } }
            removeSaved(listOf(next.chapterId))
            retainQueuedCompletions()
            done.incrementAndGet()
        } else if (!ok) {
            // Stays in the saved queue, as a failed manga chapter does: only a finished download
            // leaves it, so after a restart the row is back as Queued and waits for Resume.
            val reason = if (lastError is EmptyChapterException) {
                context.stringResource(MR.strings.novel_chapter_empty)
            } else {
                lastError?.message
            }
            setState(next.chapterId, NovelDownload.State.ERROR, reason)
            onError(shown, chapter?.name, reason, isAdult)
        }
        // Per-source pacing, by the user's delay and DownloadPacing's back-off, so a
        // rate-limited or blocked site slows down on its own without dragging healthy sources.
        val floorMs = DownloadPacing.floorFor(
            novel?.source.orEmpty(),
            novelPreferences.downloadChapterDelayMs().get(),
            DownloadPacing.parse(novelPreferences.downloadSourceDelays().get()),
            minimumMs = minimumMs,
        )
        val paceMs = DownloadPacing.next(sourceDelays[novel?.source.orEmpty()] ?: floorMs, ok, floorMs)
        novel?.source?.let { sourceDelays[it] = paceMs }
        // Only with more of this source to come, so a finished source frees its slot for one still waiting.
        if (hasQueuedFrom(claimed.source)) {
            delay(DownloadPacing.jittered(paceMs, Random.nextDouble()))
        }
    }

    private fun networkIssue() =
        downloadNetworkIssue(context.activeNetworkState(), downloadPreferences.downloadOnlyOverWifi.get())

    /** Why the drain waits for a network right now, or null when it may fetch. */
    fun networkPause(): NovelDownloadProgress.Paused? =
        networkIssue()?.let { NovelDownloadProgress.Paused(context.stringResource(it)) }

    // Ends once nothing is left to fetch too, so an emptied paused queue ends the drain.
    private suspend fun awaitNetwork(onProgress: suspend (NovelDownloadProgress) -> Unit) {
        while (hasQueued()) {
            onProgress(networkPause() ?: return)
            delay(NETWORK_RECHECK_MS)
        }
    }

    private fun hasQueued() = _queueState.value.any { it.state == NovelDownload.State.QUEUE }

    private suspend fun hasQueuedFrom(source: String) = claimLock.withLock {
        _queueState.value.any { it.state == NovelDownload.State.QUEUE && sourceOf(it.novelId) == source }
    }

    // Failed chapters stay queued but are not counted: only Resume runs them again.
    private fun pendingTotal(done: Int) = done + _queueState.value.count { it.state != NovelDownload.State.ERROR }

    private fun setState(chapterId: Long, state: NovelDownload.State, failure: String? = null) {
        _queueState.update { q ->
            q.map { if (it.chapterId == chapterId) it.copy(state = state, failure = failure) else it }
        }
    }

    companion object {
        /** How often a drain paused for the network checks it again. */
        private const val NETWORK_RECHECK_MS = 5_000L
    }
}
