package reikai.presentation.browse.extension

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.canonicalizePluginUrl
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.novel.update.LnPluginUpdate
import reikai.novel.update.LnPluginVersion
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Drives the light-novel plugin manager on the Browse → Extensions tab (Novels chip). Mirrors
 * Mihon's [eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel] sections (Updates /
 * Installed / Available) over the S2 plugin host: [NovelSourceManager] for what's installed,
 * [LnPluginInstaller.fetchRepo] across the added repos for what's available, and a version diff for
 * what has updates. Single source of network truth so one fetch feeds both Available and Updates.
 */
class LnPluginManagerScreenModel(
    manager: NovelSourceManager = Injekt.get(),
    private val installer: LnPluginInstaller = Injekt.get(),
    private val prefs: NovelPreferences = Injekt.get(),
) : StateScreenModel<LnPluginManagerScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            installer.ensureLoaded()
            manager.sources.collectLatest { sources ->
                val idToUrl = prefs.installedPluginMetadata().get()
                    .entries.associate { (url, meta) -> meta.pluginId to url }
                mutableState.update { it.copy(installed = sources, idToUrl = idToUrl) }
            }
        }
        // Re-fetch when the added-repos set changes (e.g. a backup restore or adding a repo on another
        // screen), so the tab reflects a restored repo without needing to be reopened. The pref's
        // changes() doesn't replay the current value, so do an explicit first refresh below.
        screenModelScope.launchIO {
            prefs.addedRepoUrls().changes().collectLatest { refresh() }
        }
        refresh()
    }

    fun refresh() {
        screenModelScope.launchIO {
            val repos = prefs.addedRepoUrls().get()
            mutableState.update { it.copy(isRefreshing = true, hasRepos = repos.isNotEmpty()) }
            val installedUrls = prefs.installedPluginUrls().get()
            val metadata = prefs.installedPluginMetadata().get()

            // Fetch every repo registry in parallel (network-bound), then merge in repo order so the
            // first-write-wins on URL collisions still matches the install/check surfaces. awaitAll
            // preserves the input order, so the merge order equals the repo order.
            val fetched = coroutineScope {
                repos.map { repo ->
                    async { runCatching { installer.fetchRepo(repo) }.getOrElse { emptyList() } }
                }.awaitAll()
            }
            val byUrl = LinkedHashMap<String, LnRegistryEntry>()
            fetched.forEach { entries ->
                entries.forEach { entry ->
                    val key = canonicalizePluginUrl(entry.url)
                    if (key !in byUrl) byUrl[key] = entry
                }
            }

            val available = byUrl.filterKeys { it !in installedUrls }.values.toList()
            val updates = installedUrls.mapNotNull { url ->
                val entry = byUrl[url] ?: return@mapNotNull null
                val installedVersion = metadata[url]?.version ?: return@mapNotNull null
                if (LnPluginVersion.compare(entry.version, installedVersion) > 0) {
                    LnPluginUpdate(entry = entry, installedVersion = installedVersion)
                } else {
                    null
                }
            }

            // Keep the Browse badge in sync with what the user is looking at.
            prefs.pluginUpdatesCount().set(updates.size)
            mutableState.update {
                it.copy(isRefreshing = false, hasLoaded = true, available = available, updates = updates)
            }
        }
    }

    /**
     * Force a re-load of the installed novel plugins (retrying any that failed). The novel twin of
     * manga's ExtensionManager.reloadInstalledExtensions(), wired into the shared "Re-check
     * extensions" action on this tab so it re-checks both verticals.
     */
    fun reloadInstalled() {
        screenModelScope.launchIO { installer.loadInstalled() }
    }

    fun install(entry: LnRegistryEntry) {
        val key = canonicalizePluginUrl(entry.url)
        screenModelScope.launchIO {
            mutableState.update { it.copy(inProgress = it.inProgress + key, errors = it.errors - key) }
            try {
                installer.installFromUrl(entry.url, entry.toMetadata())
                refresh()
            } catch (e: Throwable) {
                mutableState.update { it.copy(errors = it.errors + (key to (e.message ?: "Install failed"))) }
            } finally {
                mutableState.update { it.copy(inProgress = it.inProgress - key) }
            }
        }
    }

    /** Update is a reinstall of the newer registry entry: re-fetch, re-register, overwrite version. */
    fun update(update: LnPluginUpdate) = install(update.entry)

    fun updateAll() {
        state.value.updates.forEach(::update)
    }

    fun uninstall(source: NovelSource) {
        val url = state.value.idToUrl[source.id] ?: return
        screenModelScope.launchIO {
            installer.uninstall(source.id, url)
            refresh()
        }
    }

    private fun LnRegistryEntry.toMetadata() = LnInstalledPluginMetadata(
        pluginId = id,
        iconUrl = iconUrl,
        version = version,
        lang = lang,
    )

    @Immutable
    data class State(
        val isRefreshing: Boolean = false,
        val hasLoaded: Boolean = false,
        /** Whether any light-novel repo is added; lets the empty state tell "no repos" from "a repo is
         *  added but returned nothing" (e.g. unreachable). */
        val hasRepos: Boolean = false,
        val installed: List<NovelSource> = emptyList(),
        val available: List<LnRegistryEntry> = emptyList(),
        val updates: List<LnPluginUpdate> = emptyList(),
        /** plugin id -> canonical install URL, for uninstall (a [NovelSource] doesn't carry its URL). */
        val idToUrl: Map<String, String> = emptyMap(),
        /** Canonical URLs with an install/update in flight. */
        val inProgress: Set<String> = emptySet(),
        /** Canonical URL -> last install error, shown inline. */
        val errors: Map<String, String> = emptyMap(),
    ) {
        val isEmpty get() = installed.isEmpty() && available.isEmpty() && updates.isEmpty()
    }
}
