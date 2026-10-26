package reikai.novel.update

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.canonicalizePluginUrl
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRegistryFetcher
import reikai.novel.registry.LnRepoRegistries
import reikai.novel.registry.LnRepoResult
import reikai.novel.registry.byPluginUrl
import reikai.novel.registry.fetchEach
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Detects light-novel plugin updates by diffing each [NovelPreferences.addedRepoUrls] registry's
 * latest `version` against the one each installed plugin last reported when it loaded, stored on its
 * [NovelPreferences.installedPluginMetadata] record, through a single comparator ([LnPluginVersion.compare]).
 * [runIfStale] wraps [checkAndRecord] with a 6-hour cache for app launch and Browse open, while the WorkManager job bypasses
 * the cache on its own schedule. Individual repo fetch failures do not fail the batch, so a typo'd or
 * temporarily-down registry cannot hide updates from the working ones.
 */
@Inject
@SingleIn(AppScope::class)
class LnPluginUpdateChecker(
    private val fetcher: LnRegistryFetcher,
    private val registries: LnRepoRegistries,
    private val installer: LnPluginInstaller,
    private val prefs: NovelPreferences,
    private val notifier: LnPluginUpdateNotifier,
) {

    /** Checks every added repo, then records the Browse badge and the stamp [runIfStale] gates on. */
    suspend fun checkAndRecord(): List<LnPluginUpdate> {
        val repos = prefs.addedRepoUrls().get()
        val installedUrls = prefs.installedPluginUrls().get()
        val fetched = if (repos.isEmpty() || installedUrls.isEmpty()) emptyMap() else fetcher.fetchEach(repos)
        // The screens recompute the badge from their registries, so they must not keep an older copy.
        registries.accept(fetched)
        installer.recordRepos(fetched)
        val found = findPluginUpdates(
            installedUrls,
            prefs.installedPluginMetadata().get(),
            fetched.values.flatMap { (it as? LnRepoResult.Reached)?.entries.orEmpty() },
            unreachable = fetched.filterValues { it !is LnRepoResult.Reached }.keys,
        )
        notifier.setPending(found)
        // With no repo answering nothing was learned, so the next launch or Browse open checks again.
        if (fetched.isEmpty() || fetched.values.any { it is LnRepoResult.Reached }) {
            prefs.lastLnPluginCheck().set(System.currentTimeMillis())
        }
        return found.updates
    }

    /**
     * Cache-gated entry point for app launch and Browse open. Skips when the last check was less than
     * 6h ago so launching the app twice in quick succession doesn't hammer every registry.
     */
    suspend fun runIfStale() {
        val staleAfter = prefs.lastLnPluginCheck().get() + TimeUnit.HOURS.toMillis(CACHE_HOURS)
        if (System.currentTimeMillis() < staleAfter) return
        runCatchingCancellable { checkAndRecord() }.onFailure {
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
 * What one check found: the [updates] to offer, and the [undecided] plugin ids that no answering repo
 * settles while the plugin's own repo is down. An undecided plugin keeps the badge it had ([pendingIds]).
 * twin of KindListing, pinned by FindPluginUpdatesTest
 */
data class LnPluginUpdates(
    val updates: List<LnPluginUpdate>,
    val undecided: Set<String>,
) {
    /** The plugin ids pending after this check, given those pending before it. */
    fun pendingIds(previous: Set<String>): Set<String> =
        updates.mapTo(HashSet()) { it.entry.id } + (previous intersect undecided)
}

/**
 * The installed plugins a repo offers a newer version of, matched by plugin id since a repo can publish
 * a new version at a new URL. Where several repos list the id, the listing at the installed URL decides,
 * because accepting another repo's entry replaces the installed script with that repo's. With no such
 * listing the highest version wins, a tie going to the earlier repo in [entries], but only once the
 * plugin's own repo answered (every repo, before a check has recorded one): a repo that is down looks
 * the same as one that stopped listing the plugin. [unreachable] are the added repos that did not answer.
 */
fun findPluginUpdates(
    installedUrls: Set<String>,
    metadata: Map<String, LnInstalledPluginMetadata>,
    entries: List<LnRegistryEntry>,
    unreachable: Set<String>,
): LnPluginUpdates {
    val byId = entries.groupBy { it.id }
    val undecided = HashSet<String>()
    val updates = installedUrls
        .mapNotNull { url ->
            val record = metadata[url] ?: return@mapNotNull null
            val installedVersion = record.version ?: return@mapNotNull null
            val listings = byId[record.pluginId].orEmpty()
            val entry = listings.byPluginUrl(url)
                ?: if (record.repoUrl?.let { it !in unreachable } ?: unreachable.isEmpty()) {
                    listings.maxWithOrNull { a, b -> LnPluginVersion.compare(a.version, b.version) }
                } else {
                    undecided += record.pluginId
                    null
                }
                ?: return@mapNotNull null
            if (LnPluginVersion.compare(entry.version, installedVersion) > 0) {
                LnPluginUpdate(entry = entry, installedVersion = installedVersion)
            } else {
                null
            }
        }
        .distinctBy { it.entry.id }
    return LnPluginUpdates(updates, undecided)
}
