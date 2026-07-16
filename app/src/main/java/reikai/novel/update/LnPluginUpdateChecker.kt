package reikai.novel.update

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import logcat.LogPriority
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.canonicalizePluginUrl
import reikai.novel.registry.LnRegistryEntry
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Detects light-novel plugin updates by diffing each [NovelPreferences.addedRepoUrls] registry's
 * latest `version` against the version stored on each [NovelPreferences.installedPluginMetadata]
 * record at install time, using a single comparator ([LnPluginVersion.compare]).
 *
 * `check` is the pure diff. [runIfStale] wraps it with a 6-hour cache for an on-launch / on-resume
 * path (the future Browse-tab badge); the WorkManager job (lands with the Browse UI) bypasses the
 * cache on its own schedule.
 *
 * Individual repo fetch failures don't fail the batch: a typo'd repo URL or a temporarily-down
 * registry should not hide updates from the working repos.
 */
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

        // First-write-wins on URL collisions across repos so behavior matches the install surface.
        val byCanonicalUrl = LinkedHashMap<String, LnRegistryEntry>()
        for (entry in entries) {
            val key = canonicalizePluginUrl(entry.url)
            if (key !in byCanonicalUrl) byCanonicalUrl[key] = entry
        }

        return installedUrls.mapNotNull { canonicalUrl ->
            val entry = byCanonicalUrl[canonicalUrl] ?: return@mapNotNull null
            val installedVersion = metadata[canonicalUrl]?.version ?: return@mapNotNull null
            if (LnPluginVersion.compare(entry.version, installedVersion) > 0) {
                LnPluginUpdate(entry = entry, installedVersion = installedVersion)
            } else {
                null
            }
        }
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
