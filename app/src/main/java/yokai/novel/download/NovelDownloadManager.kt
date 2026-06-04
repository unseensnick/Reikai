package yokai.novel.download

import android.content.Context
import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.models.NovelChapter
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSourceManager

/**
 * App-scoped, text-only download engine for light-novel chapters. Mimics LNReader's shape: a single
 * sequential queue, one HTML file per chapter on disk, an `is_downloaded` DB flag. Deliberately NOT
 * a copy of the manga [eu.kanade.tachiyomi.data.download.DownloadManager] / Downloader stack (no
 * pages, no on-disk cache, no CBZ, no tall-image splitting).
 *
 * The actual draining runs inside [NovelDownloadJob] (a foreground worker) so downloads survive
 * backgrounding and resume after a restart; [downloadChapters] enqueues + persists, then starts the
 * job. Self-contained: each chapter's owning source is resolved from its `novelId` (chapter row ->
 * sibling Novel -> plugin id), so the same entry points work from a cold background process.
 */
class NovelDownloadManager(private val context: Context) {

    private val provider: NovelDownloadProvider by injectLazy()
    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val novelRepo: NovelRepository by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()

    private val store = NovelDownloadStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queueState = MutableStateFlow<List<NovelDownload>>(emptyList())
    val queueState: StateFlow<List<NovelDownload>> = _queueState.asStateFlow()

    /** True while [runQueue] is draining; gates a single drain. */
    private val running = AtomicBoolean(false)

    /** Per-source pacing delay (ms), adapted by [runQueue]: halved on success, doubled on failure.
     *  Only touched inside the single active drain, so a plain map is safe. */
    private val sourceDelays = HashMap<String, Long>()

    init {
        // Resume a queue persisted by a previous process: kick the job, which restores + drains.
        if (!store.isEmpty) NovelDownloadJob.start(context)
    }

    fun isChapterDownloaded(chapter: NovelChapter): Boolean {
        val id = chapter.id ?: return false
        return provider.isChapterDownloaded(chapter.novelId, id)
    }

    /** The downloaded HTML for a chapter, or null when it isn't downloaded. No host involvement. */
    fun getChapterText(chapter: NovelChapter): String? {
        val id = chapter.id ?: return null
        return provider.readChapter(chapter.novelId, id)
    }

    fun downloadChapters(chapters: List<NovelChapter>) {
        val targets = chapters.mapNotNull { ch ->
            val id = ch.id ?: return@mapNotNull null
            if (provider.isChapterDownloaded(ch.novelId, id)) return@mapNotNull null
            NovelDownload(novelId = ch.novelId, chapterId = id, url = ch.url)
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
        NovelDownloadJob.start(context)
    }

    /** Stop the running job and clear the entire pending queue. Already-downloaded chapters (files +
     *  flags) are kept; only what's still queued is discarded. */
    fun cancelAllDownloads() {
        NovelDownloadJob.stop(context)
        _queueState.value = emptyList()
        store.clear()
    }

    fun deleteChapters(chapters: List<NovelChapter>) {
        val ids = chapters.mapNotNull { it.id }.toSet()
        if (ids.isEmpty()) return
        _queueState.update { q -> q.filter { it.chapterId !in ids } }
        scope.launch {
            chapters.forEach { ch ->
                val id = ch.id ?: return@forEach
                store.remove(id)
                provider.deleteChapter(ch.novelId, id)
                chapterRepo.setDownloaded(id, false)
            }
        }
    }

    /**
     * Drain the queue sequentially until empty. Called by [NovelDownloadJob]; the worker stays
     * foreground for the duration. Restores the persisted queue first if the in-memory one is empty
     * (cold restart). [onProgress] reports `(done, total, novelTitle)` for the notification.
     */
    suspend fun runQueue(onProgress: (current: Int, total: Int, title: String) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        try {
            installer.ensureLoaded()
            if (_queueState.value.isEmpty()) {
                store.restore().takeIf { it.isNotEmpty() }?.let { _queueState.value = it }
            }
            var done = 0
            while (true) {
                val next = _queueState.value.firstOrNull { it.state == NovelDownload.State.QUEUE } ?: break
                setState(next.chapterId, NovelDownload.State.DOWNLOADING)
                val novel = novelRepo.getById(next.novelId)
                val total = done + _queueState.value.count { it.state != NovelDownload.State.ERROR }
                onProgress(done, total, novel?.title.orEmpty())
                val ok = runCatching {
                    val source = novel?.let { sourceManager.get(it.source) } ?: return@runCatching false
                    val html = source.parseChapter(next.url)
                    html.isNotBlank() && provider.writeChapter(next.novelId, next.chapterId, html)
                }.getOrElse {
                    Logger.e(it) { "Novel chapter download failed: chapter=${next.chapterId}" }
                    false
                }
                if (ok) {
                    chapterRepo.setDownloaded(next.chapterId, true)
                    store.remove(next.chapterId)
                    _queueState.update { q -> q.filter { it.chapterId != next.chapterId } }
                    done++
                } else {
                    // Don't retry forever across restarts; surface ERROR and drop from persistence.
                    setState(next.chapterId, NovelDownload.State.ERROR)
                    store.remove(next.chapterId)
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
            running.set(false)
        }
    }

    private fun setState(chapterId: Long, state: NovelDownload.State) {
        _queueState.update { q -> q.map { if (it.chapterId == chapterId) it.copy(state = state) else it } }
    }

    companion object {
        /** Per-source pacing bounds. A source cruises at [BASE_DELAY_MS] when healthy and backs off
         *  toward [MAX_DELAY_MS] (x2 per failure, /2 per success). The floor sits below LNReader's
         *  flat 1s because parseChapter's own latency already adds dead time, keeping the effective
         *  rate to a single host polite (~<=1 req/s); the adaptive cap covers sites that push back. */
        private const val BASE_DELAY_MS = 500L
        private const val MAX_DELAY_MS = 30_000L
    }
}
