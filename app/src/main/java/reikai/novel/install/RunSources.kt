package reikai.novel.install

import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap

/**
 * The sources one run reads: a download run, a library update or a metadata refresh. A run is one the user or
 * an update asked for, so a plugin whose load failed is loaded again past the wait the installer keeps for
 * screens, as a reader or details Retry loads it, once per plugin per run. Build one per run.
 */
class RunSources(private val installer: LnPluginInstaller, private val sourceManager: NovelSourceManager) {
    private val retried = ConcurrentHashMap.newKeySet<String>()

    suspend fun get(sourceId: String): NovelSource? = sourceManager.get(sourceId) ?: reloaded(sourceId)

    private suspend fun reloaded(sourceId: String): NovelSource? {
        if (installer.failures.value.values.none { it.pluginId == sourceId } || !retried.add(sourceId)) return null
        runCatchingCancellable { installer.retryLoad(sourceId) }
        return sourceManager.get(sourceId)
    }
}
