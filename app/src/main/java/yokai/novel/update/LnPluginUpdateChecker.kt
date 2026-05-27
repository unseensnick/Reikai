package yokai.novel.update

import co.touchlab.kermit.Logger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import yokai.domain.novel.NovelPreferences
import yokai.novel.host.LN_HOST_TAG
import yokai.novel.install.LnPluginInstaller
import yokai.novel.install.canonicalizePluginUrl
import yokai.novel.registry.LnRegistryEntry

/**
 * Detects light-novel plugin updates by diffing each [NovelPreferences.addedRepoUrls] registry's
 * latest `version` against the version stored on each [NovelPreferences.installedPluginMetadata]
 * record at install time. Mirrors the role manga's `ExtensionApi.checkForUpdates` plays for the
 * extension-update badge / notification, with a single comparator ([LnPluginVersion.compare])
 * instead of the manga side's split `versionCode` / `libVersion` pair.
 *
 * `check` is the pure diff and is used by both call sites:
 * - [LnPluginUpdateJob] runs it on its 12h periodic schedule and posts a notification.
 * - [runIfStale] wraps it with a 6-hour cache for the on-launch / on-resume path, mirroring
 *   `ExtensionManager.getExtensionUpdates`.
 *
 * Individual repo fetch failures don't fail the batch: the user might have a typo'd repo URL or a
 * registry that's temporarily down, and we still want to surface updates from the working repos.
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
                        Logger.w(LN_HOST_TAG, it) { "update-check: repo fetch failed for $repoUrl" }
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        // First-write-wins on URL collisions across repos so behavior matches what the install
        // surface would have produced (it dedupes by url in LnPluginBrowseScreenModel.derive).
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
     * launching the app twice in quick succession doesn't hammer every registry. The periodic
     * [LnPluginUpdateJob] bypasses this gate and always runs on its own schedule.
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
            Logger.w(LN_HOST_TAG, it) { "update-check: runIfStale failed" }
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
