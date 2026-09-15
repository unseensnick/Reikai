package reikai.novel.update

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import logcat.LogPriority
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.registry.LnRegistryEntry
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Detects light-novel plugin updates by diffing each [NovelPreferences.addedRepoUrls] registry's
 * latest `version` against the one each installed plugin last reported when it loaded, stored on its
 * [NovelPreferences.installedPluginMetadata] record, through a single comparator ([LnPluginVersion.compare]). `check` is the pure diff;
 * [runIfStale] wraps it with a 6-hour cache for the on-launch path, while the WorkManager job bypasses
 * the cache on its own schedule. Individual repo fetch failures do not fail the batch, so a typo'd or
 * temporarily-down registry cannot hide updates from the working ones.
 */
@Inject
@SingleIn(AppScope::class)
class LnPluginUpdateChecker(
    private val installer: LnPluginInstaller,
    private val prefs: NovelPreferences,
) {

    suspend fun check(): List<LnPluginUpdate> {
        val repos = prefs.addedRepoUrls().get()
        val installedUrls = prefs.installedPluginUrls().get()
        val metadata = prefs.installedPluginMetadata().get()
        if (repos.isEmpty() || installedUrls.isEmpty()) return emptyList()

        val entries: List<LnRegistryEntry> = coroutineScope {
            repos.map { repoUrl ->
                async {
                    runCatching { installer.fetchRepo(repoUrl) }.getOrElse {
                        logcat(LogPriority.WARN, it) { "update-check: repo fetch failed for $repoUrl" }
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        return findPluginUpdates(installedUrls, metadata, entries)
    }

    /**
     * Cache-gated launch / resume entry point. Skips when the last check was less than 6h ago so
     * launching the app twice in quick succession doesn't hammer every registry.
     */
    suspend fun runIfStale() {
        val now = System.currentTimeMillis()
        val staleAfter = prefs.lastLnPluginCheck().get() + TimeUnit.HOURS.toMillis(CACHE_HOURS)
        if (now < staleAfter) return
        runCatching {
            val updates = check()
            prefs.pluginUpdatesCount().set(updates.size)
            prefs.lastLnPluginCheck().set(now)
        }.onFailure {
            logcat(LogPriority.WARN, it) { "update-check: runIfStale failed" }
        }
    }

    private companion object {
        const val CACHE_HOURS = 6L
    }
}

data class LnPluginUpdate(
    val entry: LnRegistryEntry,
    val installedVersion: String,
)

/**
 * The installed plugins a repo offers a newer version of, with [entries] in repo order so the first repo
 * listing a plugin wins. Matched by plugin id: a repo can publish a new version at a new URL, and
 * matching by URL showed that as a separate plugin rather than an update.
 */
fun findPluginUpdates(
    installedUrls: Set<String>,
    metadata: Map<String, LnInstalledPluginMetadata>,
    entries: List<LnRegistryEntry>,
): List<LnPluginUpdate> {
    val byId = LinkedHashMap<String, LnRegistryEntry>()
    entries.forEach { byId.putIfAbsent(it.id, it) }
    return installedUrls
        .mapNotNull { url ->
            val record = metadata[url] ?: return@mapNotNull null
            val installedVersion = record.version ?: return@mapNotNull null
            val entry = byId[record.pluginId] ?: return@mapNotNull null
            if (LnPluginVersion.compare(entry.version, installedVersion) > 0) {
                LnPluginUpdate(entry = entry, installedVersion = installedVersion)
            } else {
                null
            }
        }
        .distinctBy { it.entry.id }
}
