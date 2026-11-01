package reikai.data.novel

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import logcat.LogPriority
import reikai.data.library.updateBySource
import reikai.data.novel.update.NovelUpdateNotifier
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.concurrent.atomic.AtomicInteger

/**
 * The novel half of Refresh library covers: re-reads each library novel's details, cover included, and
 * leaves its chapters alone, as `MetadataUpdateWorker` does for manga. One novel failing skips only it.
 */
@Inject
class NovelMetadataRefresh(
    private val novelRepository: NovelRepository,
    private val sourceManager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val libraryPreferences: LibraryPreferences,
    private val coverCache: CoverCache,
    private val notifier: NovelUpdateNotifier,
    private val novelDownloadManager: () -> NovelDownloadManager,
) {

    suspend fun run() {
        runCatchingCancellable { installer.ensureLoaded() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Could not load the novel plugins" } }
        val novels = novelRepository.getFavorites()
        val done = AtomicInteger(0)
        try {
            updateBySource(novels, sourceOf = Novel::source) { novel ->
                notifier.showProgress(novel, done.get(), novels.size)
                runCatchingCancellable { refresh(novel) }.onFailure { logcat(LogPriority.ERROR, it) }
                notifier.showProgress(novel, done.incrementAndGet(), novels.size)
            }
        } finally {
            notifier.dismissProgress()
        }
    }

    private suspend fun refresh(novel: Novel) {
        val source = sourceManager.get(novel.source) ?: return
        storeRefreshedNovel(
            novel,
            source.parseNovel(novel.url).toNovel(sourceId = source.id, favorite = novel.favorite),
            novelRepository,
            libraryPreferences,
            novelDownloadManager(),
            coverCache,
        )
    }
}
