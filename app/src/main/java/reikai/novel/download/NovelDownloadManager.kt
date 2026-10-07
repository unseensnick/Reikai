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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import reikai.data.notification.isHiddenAdult
import reikai.domain.download.SeriesCompletions
import reikai.domain.download.deletableDownloads
import reikai.domain.download.downloadNetworkIssue
import reikai.domain.download.hasRoomToDownload
import reikai.domain.download.movesDownloadFolder
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
import kotlin.random.Random

/**
 * App-scoped, text-only download engine for light-novel chapters. One sequential queue writes a self-contained HTML
 * file per chapter under a stable-name path ([NovelDownloadProvider]), and "downloaded" is decided from a disk scan
 * ([NovelDownloadCache]), so downloads survive reinstall, restore and storage moves. Draining runs inside
 * [NovelDownloadWorker], a foreground worker, so downloads survive backgrounding. Each chapter's source is resolved
 * from its `novelId`, so the entry points work from a cold background process.
 */
@Inject
@SingleIn(AppScope::class)
class NovelDownloadManager(
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queueState = MutableStateFlow<List<NovelDownload>>(emptyList())
    val queueState: StateFlow<List<NovelDownload>> = _queueState.asStateFlow()

    /** The novel whose chapter is being actively downloaded, latched across the between-chapter pacing
     *  delay so the queue UI's "Downloading" status doesn't flicker to "Queued" between chapters. */
    private val _downloadingNovelId = MutableStateFlow<Long?>(null)
    val downloadingNovelId: StateFlow<Long?> = _downloadingNovelId.asStateFlow()

    /** True while the drain worker is running (drives the queue FAB's Pause/Resume); false when the
     *  user paused or the queue is idle. Mirrors the manga DownloadManager.isDownloaderRunning. */
    val isDownloaderRunning: Flow<Boolean> get() = NovelDownloadWorker.isRunningFlow(context)

    /** Chapters finished per novel while it stayed queued, read by the download queue's cards. */
    val completions = SeriesCompletions()

    /**
     * Held for a whole [runQueue], so one drain runs at a time. A second call waits rather than
     * returning: a paused drain can still be unwinding a blocking save when Resume starts the next
     * worker, and a caller that returned then would end that worker with the queue left idle.
     */
    private val drainLock = Mutex()

    /** Per-source pacing delay (ms), adapted by [runQueue]: halved on success, doubled on failure.
     *  Only touched inside the single active drain, so a plain map is safe. */
    private val sourceDelays = HashMap<String, Long>()

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

    /** Returns once the launch restore has landed, as Mihon's Downloader.awaitQueueRestored does for manga. */
    suspend fun awaitQueueRestored() = restoreJob.join()

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
        _queueState.update { current ->
            val byId = current.associateByTo(LinkedHashMap()) { it.chapterId }
            targets.forEach { t ->
                val existing = byId[t.chapterId]
                // Re-queue an errored entry, add a brand-new one, leave a queued/active one alone.
                if (existing == null || existing.state == NovelDownload.State.ERROR) {
                    byId[t.chapterId] = t
                }
            }
            byId.values.toList()
        }
        synchronized(storeLock) { store.addAll(targets) }
        // Adding downloads implies wanting them, so clear any user pause and (re)start the drain.
        sourcePreferences.novelDownloadsPaused.set(false)
        dismissPausedNotification()
        NovelDownloadWorker.start(context)
    }

    /** Stop the running job and clear the entire pending queue. Already-downloaded chapters (files +
     *  flags) are kept; only what's still queued is discarded. */
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
     *  notification to offer Resume, since nothing restarts a queue on launch anyway. The worker is cancelled; any in-flight chapter is reset to QUEUE at the next drain start (see
     *  [runQueue]) so resume re-downloads it rather than leaving it stuck DOWNLOADING. */
    fun pauseDownloads() {
        sourcePreferences.novelDownloadsPaused.set(true)
        _downloadingNovelId.value = null
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
        val left = _queueState.updateAndGet { q -> q.filter { it.chapterId !in ids } }
        retainQueuedCompletions()
        scope.launch { removeSaved(ids) }
        // A paused queue emptied one series at a time has nothing left to resume.
        if (left.isEmpty()) dismissPausedNotification()
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
    suspend fun renameChapter(novel: Novel, oldChapter: NovelChapter, newChapter: NovelChapter) {
        val renamed = withIOContext { provider.renameChapter(novel, oldChapter, newChapter) }
        if (renamed) cache.renameChapter(novel, oldChapter, newChapter)
    }

    /**
     * Moves the novel's downloads to [newTitle]'s folder, the twin of `DownloadManager.renameManga`, pinned by
     * [movesDownloadFolder]. As there, the novel's queued chapters are dropped first, so none is written into the
     * folder being moved.
     */
    suspend fun renameNovel(novel: Novel, newTitle: String) {
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
            dequeueChapters(deletable)
            deleteChapterFiles(deletable)
        }
    }

    /** The novel's download directory, for the details overflow's Open folder; null until something
     *  is downloaded. The novel side of the lookup [reikai.presentation.details.openDownloadFolder] opens
     *  for both types, which owns the nothing-downloaded case. */
    fun findNovelDir(novel: Novel) = provider.findNovelDir(novel)

    /**
     * Drop the whole novel: everything it has queued, then its folder. The manga twin of this is
     * [eu.kanade.tachiyomi.data.download.DownloadManager.deleteManga].
     *
     * Chapter-by-chapter deletion cannot do this job. It only reaches what the disk cache already
     * reports, and a queued chapter is by definition not downloaded yet, so migrating away with
     * remove-downloads on left the worker still fetching into the source just left behind.
     */
    suspend fun awaitDeleteNovel(novel: Novel) {
        val queued = _queueState.value.filter { it.novelId == novel.id }.map { it.chapterId }
        _queueState.update { q -> q.filterNot { it.novelId == novel.id } }
        retainQueuedCompletions()
        withIOContext {
            removeSaved(queued)
            provider.deleteNovel(novel)
        }
        cache.removeNovel(novel)
    }

    private fun dequeueChapters(chapters: List<NovelChapter>) {
        val ids = chapters.map { it.id }.toSet()
        _queueState.update { q -> q.filter { it.chapterId !in ids } }
        retainQueuedCompletions()
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
     * Drain the queue sequentially until empty. Called by [NovelDownloadWorker]; the worker stays
     * foreground for the duration. Waits for the launch restore first, so a worker WorkManager reschedules
     * after a restart sees the saved queue. [onProgress] reports the chapter being downloaded, or why the
     * drain is paused.
     */
    suspend fun runQueue(
        onProgress: suspend (NovelDownloadProgress) -> Unit,
        onError: (novel: Novel?, chapterName: String?, error: String?, isAdult: Boolean) -> Unit,
    ) = drainLock.withLock {
        try {
            installer.ensureLoaded()
            restoreJob.join()
            // Everything still in the queue goes back to QUEUE, matching manga's Downloader.start. A
            // finished download leaves the queue, so what is left is either DOWNLOADING from a drain
            // that was cancelled (a user pause, a crash, a force-kill) or ERROR, which is exactly what
            // Resume is for. The loop below only picks QUEUE, so an ERROR row used to sit there
            // untouched with Resume doing nothing for it.
            _queueState.update { q -> q.map { it.copy(state = NovelDownload.State.QUEUE) } }
            var done = 0
            while (true) {
                val next = _queueState.value.firstOrNull { it.state == NovelDownload.State.QUEUE }
                if (next == null) {
                    _downloadingNovelId.value = null
                    break
                }
                // No network, or Wi-Fi only off Wi-Fi, pauses the drain instead of failing chapters, with the
                // worker kept foreground as manga's is. Re-pick afterwards: the queue may have changed meanwhile.
                if (networkIssue() != null) {
                    // Nothing is downloading, so the UI should read Queued, not Downloading.
                    _downloadingNovelId.value = null
                    awaitNetwork(onProgress)
                    continue
                }
                setState(next.chapterId, NovelDownload.State.DOWNLOADING)
                _downloadingNovelId.value = next.novelId
                val novel = novelRepo.getById(next.novelId)
                // What the notices name it by; the chapter is still saved under the source title.
                val shown = novel?.withCustomInfo(getEntryCustomInfo.await(EntryId.Novel(novel.id)))
                val chapter = chapterRepo.getById(next.chapterId)
                val total = pendingTotal(done)
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
                    continue
                }
                onProgress(NovelDownloadProgress.Downloading(done, total, shown?.title.orEmpty(), isAdult, shown))
                // Try a few times before giving up so a transient network blip or a momentarily
                // rate-limited source doesn't kill the chapter on the first stumble. The manga Downloader
                // retries each page image on this schedule; a chapter's text is one fetch, so the unit
                // here is the chapter. Backoff is separate from the cross-chapter pacing below.
                var ok = false
                var attempt = 0
                var lastError: Throwable? = null
                var connectionLost = false
                // The least delay the source declares binds its retries as well as its pacing.
                val minimumMs = novel?.let { sourceManager.get(it.source) }?.minimumRequestDelayMs ?: 0L
                while (true) {
                    // A pause cancels the worker mid-attempt, and the last attempt has no delay to rethrow it.
                    ok = runCatchingCancellable {
                        val source = novel?.let { sourceManager.get(it.source) }
                            ?: return@runCatchingCancellable false
                        if (chapter == null) return@runCatchingCancellable false
                        val html = source.parseChapter(next.url).ifBlank { throw EmptyChapterException() }
                        // A name another chapter's download holds counts as downloaded, as manga's downloader counts it
                        saver.save(novel, chapter, source, html) != NovelChapterSaver.SaveResult.FAILED
                    }.getOrElse {
                        lastError = it
                        logcat(LogPriority.ERROR, it) {
                            "Novel chapter download attempt ${attempt + 1} failed: chapter=${next.chapterId}"
                        }
                        false
                    }
                    if (ok) break
                    // A drop mid-download is a pause, not a failure: stop retrying and let the top-of-loop
                    // network check wait it out, instead of spending retries (off Wi-Fi too) and erroring it.
                    if (networkIssue() != null) {
                        connectionLost = true
                        break
                    }
                    // An empty answer is the source's, not a blip, so trying again gets the same.
                    if (attempt >= MAX_RETRIES || lastError is EmptyChapterException) break
                    attempt++
                    // Exponential backoff: 2s, 4s, 8s.
                    delay(maxOf((1L shl attempt) * 1000L, minimumMs))
                }
                if (connectionLost) {
                    // Requeue so it's re-picked when the connection returns (not left DOWNLOADING or ERROR).
                    setState(next.chapterId, NovelDownload.State.QUEUE)
                    _downloadingNovelId.value = null
                    continue
                }
                if (ok) {
                    completions.record(next.novelId)
                    _queueState.update { q -> q.filter { it.chapterId != next.chapterId } }
                    removeSaved(listOf(next.chapterId))
                    retainQueuedCompletions()
                    done++
                } else {
                    // Stays in the saved queue, as a failed manga chapter does: only a finished download
                    // leaves it, so after a restart the row is back as Queued and waits for Resume.
                    val reason = if (lastError is EmptyChapterException) {
                        context.stringResource(MR.strings.novel_chapter_empty)
                    } else {
                        lastError?.message
                    }
                    setState(next.chapterId, NovelDownload.State.ERROR, reason)
                    // Notify the user: a failed novel download was previously completely silent.
                    onError(shown, chapter?.name, reason, isAdult)
                }
                // Per-source pacing, by the user's delay and NovelDownloadPacing's back-off, so a
                // rate-limited or blocked site slows down on its own without dragging healthy sources.
                val floorMs = NovelDownloadPacing.floorFor(
                    novel?.source.orEmpty(),
                    novelPreferences.downloadChapterDelayMs().get(),
                    NovelDownloadPacing.parse(novelPreferences.downloadSourceDelays().get()),
                    minimumMs = minimumMs,
                )
                val paceMs = NovelDownloadPacing.next(sourceDelays[novel?.source] ?: floorMs, ok, floorMs)
                novel?.source?.let { sourceDelays[it] = paceMs }
                if (hasQueued()) {
                    // Up to a quarter more, never less, so the cadence is not metronomic and the
                    // user's delay stays a minimum.
                    delay((paceMs * (1.0 + Random.nextDouble() * 0.25)).toLong())
                }
            }
        } finally {
            _downloadingNovelId.value = null
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

    // Failed chapters stay queued but are not counted: only Resume runs them again.
    private fun pendingTotal(done: Int) = done + _queueState.value.count { it.state != NovelDownload.State.ERROR }

    private fun setState(chapterId: Long, state: NovelDownload.State, failure: String? = null) {
        _queueState.update { q ->
            q.map { if (it.chapterId == chapterId) it.copy(state = state, failure = failure) else it }
        }
    }

    companion object {
        /** Retry a failed chapter download this many times (after the first try) before surfacing ERROR,
         *  with exponential backoff (2s, 4s, 8s), the manga Downloader's schedule for one page image. */
        private const val MAX_RETRIES = 3

        /** How often a drain paused for the network checks it again. */
        private const val NETWORK_RECHECK_MS = 5_000L
    }
}
