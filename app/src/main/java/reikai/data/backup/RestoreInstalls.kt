package reikai.data.backup

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.serialization.Serializable
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.repository.ExtensionStoreRepository
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginLoadFailure
import reikai.novel.install.canonicalizePluginUrl
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRegistryFetcher
import reikai.novel.registry.LnRepoResult
import reikai.novel.registry.fetchEach
import reikai.util.runCatchingCancellable

/** A backup's extension app or light-novel plugin that this device lacks, as a restore installs it. */
sealed interface RestoreItem {
    val name: String

    data class App(val pkgName: String, override val name: String) : RestoreItem

    /** [url] is canonical; [pluginId] also matches the same plugin a repo lists under another address. */
    data class Plugin(val url: String, val pluginId: String?, override val name: String) : RestoreItem
}

/** What a set of repos lists, to match a backup's items against. */
data class RepoListings(
    val apps: List<Extension.Available> = emptyList(),
    val plugins: List<LnRegistryEntry> = emptyList(),
) {
    /** The listing that installs [item], the newest when several repos list it. */
    fun find(item: RestoreItem.App): Extension.Available? =
        apps.filter { it.pkgName == item.pkgName }.maxByOrNull { it.versionCode }

    fun find(item: RestoreItem.Plugin): LnRegistryEntry? =
        plugins.firstOrNull { canonicalizePluginUrl(it.url) == item.url }
            ?: item.pluginId?.let { id -> plugins.firstOrNull { it.id == id } }

    fun lists(item: RestoreItem): Boolean = when (item) {
        is RestoreItem.App -> find(item) != null
        is RestoreItem.Plugin -> find(item) != null
    }
}

/** A repo the backup had and this device lacks, read live, with the names of what only it would install. */
data class BackupRepo(
    val url: String,
    val name: String,
    val isPluginRepo: Boolean,
    // The live key, which a restore adds the repo under only if it is still the same; plugin repos have none.
    val signingKey: String?,
    val items: List<String> = emptyList(),
)

/**
 * What a restore would install: [ready] from repos already here, [repos] once trusted, [unavailable] never.
 * [unreadRepos] are the backup's repos that could not be read, or now sign with another key, so are not offered.
 */
data class RestoreInstallPlan(
    val ready: List<String> = emptyList(),
    val repos: List<BackupRepo> = emptyList(),
    val unavailable: List<String> = emptyList(),
    val unreadRepos: List<String> = emptyList(),
) {
    val isEmpty get() = ready.isEmpty() && repos.isEmpty() && unavailable.isEmpty() && unreadRepos.isEmpty()
}

/** The backup repos the user trusted on the Restore screen: app repos by index address with the key they saw. */
@Serializable
data class ApprovedRepos(
    val apps: Map<String, String> = emptyMap(),
    val plugins: Set<String> = emptySet(),
)

/**
 * Sorts [missing]: an item a repo already on the device lists installs after the restore, one only a
 * backup repo lists waits under the first such repo for the user to trust it, and the rest are named.
 * Every backup repo is offered, one listing nothing too, since trusting it is also how it comes back.
 * A backup's own key is never trusted, since a backup can be anyone's file.
 */
fun planInstalls(
    missing: List<RestoreItem>,
    device: RepoListings,
    backupRepos: List<Pair<BackupRepo, RepoListings>>,
): RestoreInstallPlan {
    val (ready, rest) = missing.partition(device::lists)
    val claimed = HashSet<RestoreItem>()
    val repos = backupRepos.map { (repo, listings) ->
        val items = rest.filter { it !in claimed && listings.lists(it) }
        claimed += items
        repo.copy(items = items.map { it.name }.sorted())
    }
    return RestoreInstallPlan(
        ready = ready.map { it.name }.sorted(),
        repos = repos,
        unavailable = rest.filterNot { it in claimed }.map { it.name }.sorted(),
    )
}

/** Reads what a backup lacks here and the repos that list it; the Restore screen and the restore share it. */
@Inject
class RestoreInstallSources(
    private val extensionManager: ExtensionManager,
    private val novelPreferences: NovelPreferences,
    private val storeRepository: ExtensionStoreRepository,
    private val storeService: ExtensionStoreService,
    private val registryFetcher: LnRegistryFetcher,
) {

    /** The keys of a backup's App settings that record its plugins and plugin repos. */
    val pluginPreferenceKeys = with(novelPreferences) {
        setOf(
            installedPluginUrls().key(),
            installedPluginMetadata().key(),
            seenNovelSources().key(),
            addedRepoUrls().key(),
        )
    }

    /** The backup's apps and plugins this device has none of, read past the adult-source gate. */
    suspend fun missing(apps: List<BackupExtension>, preferences: Map<String, PreferenceValue>): List<RestoreItem> {
        return missingApps(apps) + missingPlugins(preferences)
    }

    private suspend fun missingApps(apps: List<BackupExtension>): List<RestoreItem.App> {
        if (apps.isEmpty()) return emptyList()
        val installed = with(extensionManager) {
            getLoadedExtensions() + getNotLoadedExtensions() + getLoadedNovelExtensions() +
                getNotLoadedNovelExtensions()
        }.mapTo(HashSet()) { it.pkgName }
        return apps.filterNot { it.pkgName in installed }.map { RestoreItem.App(it.pkgName, it.name) }
    }

    // A plugin installed from another repo has another URL, so its id answers too.
    private fun missingPlugins(backup: Map<String, PreferenceValue>): List<RestoreItem.Plugin> {
        val urls = (backup[novelPreferences.installedPluginUrls().key()] as? StringSetPreferenceValue)?.value
        if (urls.isNullOrEmpty()) return emptyList()
        fun stored(key: String) = (backup[key] as? StringPreferenceValue)?.value
        val metadata = stored(novelPreferences.installedPluginMetadata().key())
            ?.let(NovelPreferences::decodePluginMetadata).orEmpty()
        val seen = stored(novelPreferences.seenNovelSources().key())
            ?.let(NovelPreferences::decodeSeenNovelSources).orEmpty()
        val installedUrls = novelPreferences.installedPluginUrls().get()
        val installedIds = novelPreferences.installedPluginMetadata().get().values.mapTo(HashSet()) { it.pluginId }
        return urls
            .filterNot { it in installedUrls || metadata[it]?.pluginId in installedIds }
            .map { url ->
                val pluginId = metadata[url]?.pluginId
                RestoreItem.Plugin(
                    canonicalizePluginUrl(url),
                    pluginId,
                    LnPluginLoadFailure.pluginName(url, seen[pluginId]),
                )
            }
    }

    /** What the repos on this device list now, each read live. */
    suspend fun device(): RepoListings = RepoListings(
        apps = storeRepository.fetchExtensionsByStore().values.flatMap { it.getOrDefault(emptyList()) },
        plugins = registryFetcher.fetchEach(novelPreferences.addedRepoUrls().get()).values
            .flatMap { (it as? LnRepoResult.Reached)?.entries.orEmpty() },
    )

    /** Sorts [missing] against the device's repos and the backup's repos this device lacks. */
    suspend fun plan(
        missing: List<RestoreItem>,
        stores: List<BackupExtensionStore>,
        preferences: Map<String, PreferenceValue>,
    ): RestoreInstallPlan {
        val deviceStores = storeRepository.getAll().mapTo(HashSet()) { it.indexUrl }
        val devicePluginRepos = novelPreferences.addedRepoUrls().get()
        val pluginRepos = (preferences[novelPreferences.addedRepoUrls().key()] as? StringSetPreferenceValue)
            ?.value.orEmpty() - devicePluginRepos
        val read = stores.filterNot { it.indexUrl in deviceStores }.map { it.name to readStore(it) } +
            pluginRepos.map { it to readPluginRepo(it) }
        // Nothing missing needs no listings of the device's repos, which are read over the network.
        val device = if (missing.isEmpty()) RepoListings() else device()
        return planInstalls(missing, device, read.mapNotNull { it.second })
            .copy(unreadRepos = read.filter { it.second == null }.map { it.first }.sorted())
    }

    // Null when the store can't be read or now signs with another key than the backup recorded.
    private suspend fun readStore(backup: BackupExtensionStore): Pair<BackupRepo, RepoListings>? {
        val live = storeService.fetch(backup.indexUrl).getOrNull() ?: return null
        if (live.signingKey != backup.signingKey) return null
        val apps = storeService.getExtensions(live).getOrNull() ?: return null
        return BackupRepo(live.indexUrl, live.name, isPluginRepo = false, signingKey = live.signingKey) to
            RepoListings(apps = apps)
    }

    private suspend fun readPluginRepo(url: String): Pair<BackupRepo, RepoListings>? {
        val entries = runCatchingCancellable { registryFetcher.fetchRepo(url) }.getOrNull() ?: return null
        return BackupRepo(url, url, isPluginRepo = true, signingKey = null) to RepoListings(plugins = entries)
    }
}
