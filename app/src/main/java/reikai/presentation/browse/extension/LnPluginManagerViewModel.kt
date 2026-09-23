package reikai.presentation.browse.extension

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.LnPluginLoadFailure
import reikai.novel.install.canonicalizePluginUrl
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRepoRegistries
import reikai.novel.registry.LnRepoResult
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.novel.update.LnPluginUpdate
import reikai.novel.update.findPluginUpdates
import tachiyomi.core.common.util.lang.launchIO
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the light-novel plugin manager on the Browse → Extensions tab (Novels chip). Mirrors
 * Mihon's [eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel] sections (Updates /
 * Installed / Available) over the plugin host: [NovelSourceManager] for what's installed, the
 * shared [LnRepoRegistries] for what's available, and a version diff for what has updates. Both
 * lists are derived from the registries already fetched and the installed plugins' records, so an
 * install, an uninstall or a return to the tab downloads nothing.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class LnPluginManagerViewModel(
    manager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val registries: LnRepoRegistries,
    private val prefs: NovelPreferences,
) : ViewModel() {

    /** Canonical URLs with an install in flight, and the last error per URL. */
    private val installs = MutableStateFlow(Installs())

    // The registry holds the extension apps' catalogues too, which are installed and removed as apps.
    private val installed: Flow<List<NovelSource>> = flow {
        installer.ensureLoaded()
        emitAll(manager.sources.map { sources -> sources.filterIsInstance<LnPluginSource>() })
    }

    /** Null until the registries first load. */
    private val fetched: Flow<RepoFetch?> = combine(
        registries.results,
        prefs.installedPluginUrls().changes(),
        prefs.installedPluginMetadata().changes(),
        ::repoFetch,
    )
        // Keep the Browse badge in sync with what the user is looking at.
        .onEach { prefs.pluginUpdatesCount().set(it.updates.size) }
        .onStart<RepoFetch?> { emit(null) }

    val state: StateFlow<State> = combine(
        installed,
        installer.failures,
        fetched,
        registries.isRefreshing,
        installs,
    ) { installed, failures, fetched, refreshing, installs ->
        State(
            isRefreshing = refreshing,
            hasLoaded = fetched != null,
            hasRepos = fetched?.hasRepos ?: false,
            installed = installed,
            notLoaded = failures.values.toList(),
            installedVersions = fetched?.installedVersions.orEmpty(),
            available = fetched?.available.orEmpty(),
            updates = fetched?.updates.orEmpty(),
            inProgress = installs.inProgress,
            errors = installs.errors,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    fun refresh() {
        viewModelScope.launchIO { registries.refresh() }
    }

    private fun repoFetch(
        results: Map<String, LnRepoResult>,
        installedUrls: Set<String>,
        metadata: Map<String, LnInstalledPluginMetadata>,
    ): RepoFetch {
        // Merge in repo order, so first-write-wins on URL collisions matches the install/check surfaces.
        val registries = results.values.map { (it as? LnRepoResult.Reached)?.entries.orEmpty() }
        val byUrl = LinkedHashMap<String, LnRegistryEntry>()
        registries.forEach { entries ->
            entries.forEach { entry ->
                val key = canonicalizePluginUrl(entry.url)
                if (key !in byUrl) byUrl[key] = entry
            }
        }
        return RepoFetch(
            hasRepos = results.isNotEmpty(),
            available = byUrl.filterKeys { it !in installedUrls }.values.toList(),
            updates = findPluginUpdates(
                installedUrls,
                metadata,
                registries.flatten(),
                everyRepoReached = results.values.all { it is LnRepoResult.Reached },
            ),
            installedVersions = metadata.values
                .mapNotNull { meta -> meta.version?.let { meta.pluginId to it } }
                .toMap(),
        )
    }

    /**
     * Force a re-load of the installed novel plugins (retrying any that failed). The novel twin of
     * manga's ExtensionManager.reloadInstalledExtensions(), wired into the shared "Re-check
     * extensions" action on this tab so it re-checks both verticals.
     */
    fun reloadInstalled() {
        viewModelScope.launchIO { installer.loadInstalled() }
    }

    fun install(entry: LnRegistryEntry) = install(entry.url, entry.toMetadata())

    /** Fetches the script again for a plugin whose stored one is gone, keeping what its record knew. */
    fun reinstall(failure: LnPluginLoadFailure) = install(
        failure.url,
        LnInstalledPluginMetadata(
            pluginId = failure.pluginId.orEmpty(),
            iconUrl = failure.iconUrl,
            version = failure.version,
            lang = failure.lang,
        ),
    )

    private fun install(url: String, metadata: LnInstalledPluginMetadata) {
        val key = canonicalizePluginUrl(url)
        // Claimed before the launch, so a second tap on any entry point finds it taken: two installs
        // of one plugin share its temp file, and the second rename fails.
        val claimed = installs.getAndUpdate {
            if (key in it.inProgress) it else it.copy(inProgress = it.inProgress + key, errors = it.errors - key)
        }
        if (key in claimed.inProgress) return
        viewModelScope.launchIO {
            try {
                installer.installFromUrl(url, metadata)
            } catch (e: Throwable) {
                installs.update { it.copy(errors = it.errors + (key to (e.message ?: "Install failed"))) }
            } finally {
                installs.update { it.copy(inProgress = it.inProgress - key) }
            }
        }
    }

    /** Update is a reinstall of the newer registry entry: re-fetch, re-register, overwrite version. */
    fun update(update: LnPluginUpdate) = install(update.entry)

    fun uninstall(source: NovelSource) {
        viewModelScope.launchIO {
            // Uninstall by plugin id; the installer resolves the URL(s) from fresh metadata. A cached
            // id->url map lags a same-session install (install registers the source before persisting
            // metadata), which silently no-op'd the trash button until an app restart.
            installer.uninstall(source.id)
        }
    }

    /** A plugin that never loaded has no source to name it by, so it goes by its record instead. */
    fun uninstall(failure: LnPluginLoadFailure) {
        viewModelScope.launchIO {
            installer.uninstall(failure.pluginId.orEmpty(), failure.url)
        }
    }

    private fun LnRegistryEntry.toMetadata() = LnInstalledPluginMetadata(
        pluginId = id,
        iconUrl = iconUrl,
        version = version,
        lang = lang,
    )

    private data class Installs(val inProgress: Set<String> = emptySet(), val errors: Map<String, String> = emptyMap())

    private class RepoFetch(
        val hasRepos: Boolean,
        val available: List<LnRegistryEntry>,
        val updates: List<LnPluginUpdate>,
        val installedVersions: Map<String, String>,
    )

    @Immutable
    data class State(
        val isRefreshing: Boolean = false,
        val hasLoaded: Boolean = false,
        /** Whether any light-novel repo is added; lets the empty state tell "no repos" from "a repo is
         *  added but returned nothing" (e.g. unreachable). */
        val hasRepos: Boolean = false,
        val installed: List<NovelSource> = emptyList(),
        /** Installed plugins whose last load failed, which no source stands for. */
        val notLoaded: List<LnPluginLoadFailure> = emptyList(),
        /** Plugin id -> installed version. A manga row reads its version off the package;
         *  a plugin's only record of one is the metadata written at install. */
        val installedVersions: Map<String, String> = emptyMap(),
        val available: List<LnRegistryEntry> = emptyList(),
        val updates: List<LnPluginUpdate> = emptyList(),
        /** Canonical URLs with an install/update in flight. */
        val inProgress: Set<String> = emptySet(),
        /** Canonical URL -> last install error, shown inline. */
        val errors: Map<String, String> = emptyMap(),
    )
}
