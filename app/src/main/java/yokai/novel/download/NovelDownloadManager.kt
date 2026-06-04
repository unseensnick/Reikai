package yokai.novel.download

import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Self-contained: each chapter's owning source is resolved from its `novelId` (chapter row ->
 * sibling Novel -> plugin id), so the engine needs no screen state and the same entry points work
 * from a future background WorkManager job (Slice 2). Registered as a Koin `single` and reached via
 * `injectLazy` like the manga DownloadManager.
 */
class NovelDownloadManager {

    private val provider: NovelDownloadProvider by injectLazy()
    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val novelRepo: NovelRepository by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queueState = MutableStateFlow<List<NovelDownload>>(emptyList())
    val queueState: StateFlow<List<NovelDownload>> = _queueState.asStateFlow()

    /** Guards a single drain loop. */
    private val draining = AtomicBoolean(false)

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
        startDraining()
    }

    fun deleteChapters(chapters: List<NovelChapter>) {
        val ids = chapters.mapNotNull { it.id }.toSet()
        if (ids.isEmpty()) return
        _queueState.update { q -> q.filter { it.chapterId !in ids } }
        scope.launch {
            chapters.forEach { ch ->
                val id = ch.id ?: return@forEach
                provider.deleteChapter(ch.novelId, id)
                chapterRepo.setDownloaded(id, false)
            }
        }
    }

    private fun startDraining() {
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                // A background-cold process has an empty source registry; ensure plugins are loaded
                // before any parseChapter (mirrors NovelUpdateJob.doWork).
                installer.ensureLoaded()
                while (true) {
                    val next = _queueState.value.firstOrNull { it.state == NovelDownload.State.QUEUE }
                        ?: break
                    setState(next.chapterId, NovelDownload.State.DOWNLOADING)
                    val ok = runCatching { downloadOne(next) }.getOrElse {
                        Logger.e(it) { "Novel chapter download failed: chapter=${next.chapterId}" }
                        false
                    }
                    if (ok) {
                        chapterRepo.setDownloaded(next.chapterId, true)
                        // Done: leave the queue. The DB flag now carries the downloaded state.
                        _queueState.update { q -> q.filter { it.chapterId != next.chapterId } }
                    } else {
                        setState(next.chapterId, NovelDownload.State.ERROR)
                    }
                }
            } finally {
                draining.set(false)
            }
            // Catch an enqueue that raced the loop's exit, so the queue never stalls with work left.
            if (_queueState.value.any { it.state == NovelDownload.State.QUEUE }) startDraining()
        }
    }

    private suspend fun downloadOne(download: NovelDownload): Boolean {
        val novel = novelRepo.getById(download.novelId) ?: return false
        val source = sourceManager.get(novel.source) ?: return false
        val html = source.parseChapter(download.url)
        if (html.isBlank()) return false
        return provider.writeChapter(download.novelId, download.chapterId, html)
    }

    private fun setState(chapterId: Long, state: NovelDownload.State) {
        _queueState.update { q -> q.map { if (it.chapterId == chapterId) it.copy(state = state) else it } }
    }
}
