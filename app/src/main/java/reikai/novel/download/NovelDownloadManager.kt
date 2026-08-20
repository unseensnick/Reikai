package reikai.novel.download

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.activeNetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * App-scoped, text-only download engine for light-novel chapters. One sequential queue writes a
 * self-contained HTML file per chapter under a stable-name path ([NovelDownloadProvider]), and
 * "downloaded" is decided from a disk scan ([NovelDownloadCache]), so downloads survive reinstall,
 * restore and storage moves. Lighter than the manga stack (no pages, no CBZ, no tall-image splitting)
 * but sharing its naming and disk-cache approach. Draining runs inside [NovelDownloadJob], a
 * foreground worker, so downloads survive backgrounding. Each chapter's source is resolved from its
 * `novelId`, so the entry points work from a cold background process.
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
    private val networkHelper: NetworkHelper,
    private val downloadPreferences: DownloadPreferences,
    private val sourcePreferences: ReikaiSourcePreferences,
) {

    private val store = NovelDownloadStore(context, chapterRepo)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queueState = MutableStateFlow<List<NovelDownload>>(emptyList())
    val queueState: StateFlow<List<NovelDownload>> = _queueState.asStateFlow()

    /** The novel whose chapter is being actively downloaded, latched across the between-chapter pacing
     *  delay so the queue UI's "Downloading" status doesn't flicker to "Queued" between chapters. */
    private val _downloadingNovelId = MutableStateFlow<Long?>(null)
    val downloadingNovelId: StateFlow<Long?> = _downloadingNovelId.asStateFlow()

    /** True while the drain worker is running (drives the queue FAB's Pause/Resume); false when the
     *  user paused or the queue is idle. Mirrors the manga DownloadManager.isDownloaderRunning. */
    val isDownloaderRunning: Flow<Boolean> get() = NovelDownloadJob.isRunningFlow(context)

    /** True while [runQueue] is draining; gates a single drain. */
    private val running = AtomicBoolean(false)

    /** Per-source pacing delay (ms), adapted by [runQueue]: halved on success, doubled on failure.
     *  Only touched inside the single active drain, so a plain map is safe. */
    private val sourceDelays = HashMap<String, Long>()

    init {
        // Load the persisted queue into memory on launch, off the main thread (restore() reads the DB),
        // so the queue screen shows it even while paused. Previously only the drain restored it, but a
        // paused restart no longer starts the drain, which otherwise left the queue invisible and
        // unresumable. Constructing this manager therefore reads the database and can start the
        // download worker, which is why its consumers take a Provider rather than the manager itself.
        scope.launch {
            val restored = store.restore()
            if (restored.isNotEmpty() && _queueState.value.isEmpty()) {
                _queueState.value = restored
            }
            // Resume a queue persisted by a previous process, unless the user left it paused.
            if (restored.isNotEmpty() && !sourcePreferences.novelDownloadsPaused.get()) {
                NovelDownloadJob.start(context)
            }
        }
    }

    fun isChapterDownloaded(novel: Novel, chapter: NovelChapter): Boolean =
        cache.isChapterDownloaded(novel, chapter)

    /** How many chapters of [novel] are on disk, from the same cache the reader consults. */
    fun getDownloadCount(novel: Novel): Int = cache.getDownloadCount(novel)

    /** The downloaded HTML for a chapter, or null when it isn't downloaded. No host involvement. */
    fun getChapterText(novel: Novel, chapter: NovelChapter): String? =
        provider.readChapter(novel, chapter)

    fun downloadChapters(chapters: List<NovelChapter>) {
        // Callers filter out already-downloaded chapters via the cache before enqueuing. There is no
        // enqueue-time or drain-time disk check (it would need each chapter's owning Novel), so an
        // unfiltered caller would re-download an already-present chapter.
        val targets = chapters.map { ch ->
            NovelDownload(novelId = ch.novelId, chapterId = ch.id, url = ch.url)
        }
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
        store.addAll(targets)
        // Adding downloads implies wanting them, so clear any user pause and (re)start the drain.
        sourcePreferences.novelDownloadsPaused.set(false)
        NovelDownloadJob.start(context)
    }

    /** Stop the running job and clear the entire pending queue. Already-downloaded chapters (files +
     *  flags) are kept; only what's still queued is discarded. */
    fun cancelAllDownloads() {
        NovelDownloadJob.stop(context)
        sourcePreferences.novelDownloadsPaused.set(false)
        _queueState.value = emptyList()
        store.clear()
    }

    /** User pause: stop the drain without clearing the queue, persisted so a restart stays paused. The
     *  worker is cancelled; any in-flight chapter is reset to QUEUE at the next drain start (see
     *  [runQueue]) so resume re-downloads it rather than leaving it stuck DOWNLOADING. */
    fun pauseDownloads() {
        sourcePreferences.novelDownloadsPaused.set(true)
        _downloadingNovelId.value = null
        NovelDownloadJob.stop(context)
    }

    /** User resume: clear the pause and restart the drain. */
    fun startDownloads() {
        sourcePreferences.novelDownloadsPaused.set(false)
        NovelDownloadJob.start(context)
    }

    /** Drop chapters from the pending queue without deleting any downloaded file/flag (the chip's
     *  CANCEL action). Sibling of [deleteChapters], which also removes the on-disk file. */
    fun cancelDownloads(chapterIds: List<Long>) {
        val ids = chapterIds.toSet()
        if (ids.isEmpty()) return
        _queueState.update { q -> q.filter { it.chapterId !in ids } }
        scope.launch { ids.forEach { store.remove(it) } }
    }

    /** Bump a queued chapter to the front so it downloads next. No-op if it isn't queued (already
     *  downloading, or not enqueued). In-memory only: the persisted store keeps its order, so a cold
     *  restart drains in the original sequence. */
    fun startDownloadNow(chapterId: Long) {
        _queueState.update { q ->
            val idx = q.indexOfFirst { it.chapterId == chapterId }
            if (idx <= 0) q else listOf(q[idx]) + q.filterIndexed { i, _ -> i != idx }
        }
        NovelDownloadJob.start(context)
    }

    /** Replace the pending queue order (drag-to-reorder or sort from the queue screen) and persist it,
     *  so a cold restart drains in the new order. The active drain re-reads the queue each step, so a
     *  reorder takes effect on the next pick and the in-flight chapter is left alone. */
    fun reorderQueue(downloads: List<NovelDownload>) {
        // update{} (not value=) so this composes with the active drain's atomic removals instead of
        // overwriting them, which could otherwise re-add a chapter the drain just completed.
        _queueState.update { downloads }
        scope.launch { store.replaceAll(downloads) }
        if (downloads.any { it.state == NovelDownload.State.QUEUE }) NovelDownloadJob.start(context)
    }

    /** Relocate a downloaded chapter's file after a source re-title, keeping the disk index in sync.
     *  Called from the chapter sync; no-op when the chapter isn't downloaded. */
    suspend fun renameChapter(novel: Novel, oldChapter: NovelChapter, newChapter: NovelChapter) {
        val renamed = withIOContext { provider.renameChapter(novel, oldChapter, newChapter) }
        if (renamed) cache.renameChapter(novel, oldChapter, newChapter)
    }

    fun deleteChapters(chapters: List<NovelChapter>) {
        if (chapters.isEmpty()) return
        dequeueChapters(chapters)
        scope.launch { deleteChapterFiles(chapters) }
    }

    /**
     * [deleteChapters], but the caller waits for the files to actually go. Migration needs this: a
     * detached delete returns before anything is deleted, so a failure can neither fail the row nor
     * be retried, and the next step runs against files that are still there.
     */
    suspend fun awaitDeleteChapters(chapters: List<NovelChapter>) {
        if (chapters.isEmpty()) return
        dequeueChapters(chapters)
        deleteChapterFiles(chapters)
    }

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
        withIOContext {
            queued.forEach { store.remove(it) }
            provider.deleteNovel(novel)
        }
        cache.removeNovel(novel)
    }

    private fun dequeueChapters(chapters: List<NovelChapter>) {
        val ids = chapters.map { it.id }.toSet()
        _queueState.update { q -> q.filter { it.chapterId !in ids } }
    }

    private suspend fun deleteChapterFiles(chapters: List<NovelChapter>) {
        val novelsById = chapters.map { it.novelId }.distinct()
            .mapNotNull { id -> novelRepo.getById(id)?.let { id to it } }
            .toMap()
        chapters.forEach { ch ->
            store.remove(ch.id)
            val novel = novelsById[ch.novelId] ?: return@forEach
            provider.deleteChapter(novel, ch)
            cache.removeChapter(novel, ch)
        }
    }

    /**
     * Drain the queue sequentially until empty. Called by [NovelDownloadJob]; the worker stays
     * foreground for the duration. Restores the persisted queue first if the in-memory one is empty
     * (cold restart). [onProgress] reports `(done, total, novelTitle)` for the notification.
     */
    suspend fun runQueue(
        onProgress: (current: Int, total: Int, title: String) -> Unit,
        onError: (novelTitle: String?, error: String?) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        try {
            installer.ensureLoaded()
            if (_queueState.value.isEmpty()) {
                store.restore().takeIf { it.isNotEmpty() }?.let { _queueState.value = it }
            }
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
                // A lost connection (airplane mode, dropped network) isn't a download failure: pause and
                // wait for it to return instead of erroring chapters, mirroring the Wi-Fi-only pause below.
                if (!context.activeNetworkState().isOnline) {
                    _downloadingNovelId.value = null
                    while (!context.activeNetworkState().isOnline) {
                        val pending = done + _queueState.value.count { it.state != NovelDownload.State.ERROR }
                        onProgress(done, pending, context.stringResource(MR.strings.download_notifier_no_network))
                        delay(WIFI_RECHECK_MS)
                    }
                    continue
                }
                // Honor the shared "download only over Wi-Fi" preference: pause (don't drop) the drain
                // while it's on and we're off Wi-Fi, and resume on its own once Wi-Fi is back. The worker
                // stays foreground showing a "no Wi-Fi" notice, mirroring the manga DownloadJob (which keeps
                // its worker alive and watches the network) instead of ending with the queue stuck.
                if (downloadPreferences.downloadOnlyOverWifi.get() && !context.activeNetworkState().isWifi) {
                    // Paused off Wi-Fi: nothing is downloading, so the UI should read Queued, not Downloading.
                    _downloadingNovelId.value = null
                    while (downloadPreferences.downloadOnlyOverWifi.get() && !context.activeNetworkState().isWifi) {
                        val pending = done + _queueState.value.count { it.state != NovelDownload.State.ERROR }
                        onProgress(done, pending, context.stringResource(MR.strings.download_notifier_text_only_wifi))
                        delay(WIFI_RECHECK_MS)
                    }
                    continue // re-pick the next chapter: the queue may have changed while we waited
                }
                setState(next.chapterId, NovelDownload.State.DOWNLOADING)
                _downloadingNovelId.value = next.novelId
                val novel = novelRepo.getById(next.novelId)
                val chapter = chapterRepo.getById(next.chapterId)
                val total = done + _queueState.value.count { it.state != NovelDownload.State.ERROR }
                onProgress(done, total, novel?.title.orEmpty())
                // Try a few times before giving up so a transient network blip or a momentarily
                // rate-limited source doesn't kill the chapter on the first stumble (mirrors the manga
                // Downloader). Backoff is per-chapter, separate from the cross-chapter pacing below.
                var ok = false
                var attempt = 0
                var lastError: Throwable? = null
                var connectionLost = false
                while (true) {
                    ok = runCatching {
                        val source = novel?.let { sourceManager.get(it.source) } ?: return@runCatching false
                        if (chapter == null) return@runCatching false
                        val html = source.parseChapter(next.url)
                        if (html.isBlank()) return@runCatching false
                        // Embed inline images so the saved file reads offline (see inlineChapterImages).
                        val selfContained = inlineChapterImages(html, source.site, networkHelper.client)
                        provider.writeChapter(novel, chapter, selfContained)
                    }.getOrElse {
                        lastError = it
                        logcat(LogPriority.ERROR, it) {
                            "Novel chapter download attempt ${attempt + 1} failed: chapter=${next.chapterId}"
                        }
                        false
                    }
                    if (ok) break
                    // A drop mid-download is a pause, not a failure: stop retrying and let the top-of-loop
                    // connectivity check wait it out, instead of spending retries and erroring the chapter.
                    if (!context.activeNetworkState().isOnline) {
                        connectionLost = true
                        break
                    }
                    if (attempt >= MAX_RETRIES) break
                    attempt++
                    // Exponential backoff: 2s, 4s, 8s.
                    delay((1L shl attempt) * 1000L)
                }
                if (connectionLost) {
                    // Requeue so it's re-picked when the connection returns (not left DOWNLOADING or ERROR).
                    setState(next.chapterId, NovelDownload.State.QUEUE)
                    _downloadingNovelId.value = null
                    continue
                }
                if (ok) {
                    if (novel != null && chapter != null) cache.addChapter(novel, chapter)
                    store.remove(next.chapterId)
                    _queueState.update { q -> q.filter { it.chapterId != next.chapterId } }
                    done++
                } else {
                    // Don't retry forever across restarts; surface ERROR and drop from persistence.
                    setState(next.chapterId, NovelDownload.State.ERROR)
                    store.remove(next.chapterId)
                    // Notify the user: a failed novel download was previously completely silent.
                    onError(novel?.title, lastError?.message)
                }
                // Per-source adaptive pacing. Downloads are sequential and LN plugins share one client
                // with no per-source rate limiter, so each source self-throttles: halve its delay
                // toward the floor on success, double it toward the cap on failure. A rate-limited or
                // blocked site backs off on its own without dragging healthy sources.
                novel?.source?.let { sourceId ->
                    val current = sourceDelays[sourceId] ?: BASE_DELAY_MS
                    sourceDelays[sourceId] = if (ok) {
                        (current / 2).coerceAtLeast(BASE_DELAY_MS)
                    } else {
                        (current.coerceAtLeast(BASE_DELAY_MS) * 2).coerceAtMost(MAX_DELAY_MS)
                    }
                }
                if (_queueState.value.any { it.state == NovelDownload.State.QUEUE }) {
                    val paceMs = novel?.source?.let { sourceDelays[it] } ?: BASE_DELAY_MS
                    // +/-25% jitter so the cadence isn't perfectly metronomic.
                    delay((paceMs * (0.75 + Random.nextDouble() * 0.5)).toLong())
                }
            }
        } finally {
            _downloadingNovelId.value = null
            running.set(false)
        }
    }

    private fun setState(chapterId: Long, state: NovelDownload.State) {
        _queueState.update { q -> q.map { if (it.chapterId == chapterId) it.copy(state = state) else it } }
    }

    companion object {
        /** Per-source pacing bounds. A source cruises at [BASE_DELAY_MS] when healthy and backs off
         *  toward [MAX_DELAY_MS] (x2 per failure, /2 per success). The floor sits below LNReader's flat
         *  1s because parseChapter's own latency already adds dead time, keeping the effective rate to
         *  a single host polite (~<=1 req/s); the adaptive cap covers sites that push back. */
        private const val BASE_DELAY_MS = 500L
        private const val MAX_DELAY_MS = 30_000L

        /** Retry a failed chapter download this many times (after the first try) before surfacing ERROR,
         *  with exponential backoff (2s, 4s, 8s), mirroring the manga Downloader. */
        private const val MAX_RETRIES = 3

        /** How often to re-check connectivity while a "download only over Wi-Fi" drain is paused off Wi-Fi. */
        private const val WIFI_RECHECK_MS = 5_000L
    }
}
