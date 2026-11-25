package reikai.data.novel

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import logcat.LogPriority
import reikai.data.library.UpdateProgressList
import reikai.data.library.updateBySource
import reikai.data.novel.update.NovelUpdateNotifier
import reikai.domain.download.SourceContacts
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.source.SourceKey
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.RunSources
import reikai.novel.source.NovelSourceManager
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The novel half of Refresh library covers: re-reads each library novel's details, cover included, and
 * leaves its chapters alone, as `MetadataUpdateWorker` does for manga. One novel failing skips only it.
 * Progress goes through that worker's own notice, whose Cancel is the one that stops this.
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
    private val sourceContacts: SourceContacts,
) {

    /** How many novels [run] refreshes, so a caller can show one bar over its own pass and this one. */
    suspend fun libraryCount(): Int = novelRepository.getFavorites().size

    /** [onProgress] gets the titles as a lookup, so a tick the notice throttles away reads nothing. */
    suspend fun run(onProgress: suspend (titles: suspend () -> List<String>, done: Int, total: Int) -> Unit) {
        runCatchingCancellable { installer.ensureLoaded() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Could not load the novel plugins" } }
        val novels = novelRepository.getFavorites()
        val updates = UpdateProgressList<Novel>(novels.size) { updating, done, total ->
            onProgress({ notifier.visibleTitles(updating) }, done, total)
        }
        val sources = RunSources(installer, sourceManager)
        updateBySource(novels, sourceOf = { SourceKey.Novel(it.source) }, sourceContacts) { novel ->
            updates.around(novel) {
                runCatchingCancellable { refresh(novel, sources) }.onFailure { logcat(LogPriority.ERROR, it) }
            }
        }
    }

    // Under a local series' hold, at the address it has then, as refreshNovelFromSource parses: an organize or relink
    // since the run read the library may have moved it.
    private suspend fun refresh(novel: Novel, sources: RunSources) {
        val source = sources.get(novel.source) ?: return
        source.seriesLocked(novel, novelRepository) { current ->
            storeRefreshedNovel(
                current,
                source.parseNovel(current.url).toNovel(sourceId = source.id, favorite = current.favorite)
                    .withoutPageCount(),
                novelRepository,
                libraryPreferences,
                novelDownloadManager(),
                coverCache,
            )
        }
    }
}
