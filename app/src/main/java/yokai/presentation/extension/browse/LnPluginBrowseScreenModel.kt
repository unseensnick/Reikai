package yokai.presentation.extension.browse

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.novel.NovelPreferences
import yokai.novel.host.LnPluginHost
import yokai.novel.install.LnPluginInstaller
import yokai.novel.install.canonicalizePluginUrl
import yokai.novel.registry.LnRegistryEntry

/**
 * Aggregates LN plugins across every URL in [NovelPreferences.addedRepoUrls] for the upcoming
 * Light novels sub-tab in the Browse → Extensions bottom sheet (Phase 8 follow-up CR6).
 *
 * Re-derives the list whenever either pref changes:
 *  - [NovelPreferences.addedRepoUrls] — user added or removed a repo (Phase 8 follow-up CR1-CR3).
 *  - [NovelPreferences.installedPluginUrls] — a plugin was installed or uninstalled and rows
 *    need updated badges.
 *
 * Fetches all repo registries in parallel; individual failures are counted (toast in the UI) but
 * don't fail the whole list. Empty repos set yields an empty Success state (UI shows the "add a
 * repo" empty-state pointer).
 *
 * `install` / `uninstall` take the [LnPluginHost] from the call site because the host has a
 * Context-dependent lifecycle (WebView, Coil scope) the composable owns; the screen model only
 * orchestrates data.
 */
class LnPluginBrowseScreenModel :
    StateScreenModel<LnPluginBrowseScreenModel.State>(State.Loading), KoinComponent {

    private val novelPrefs: NovelPreferences by inject()
    private val installer: LnPluginInstaller by inject()

    private val _busyEntryIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    val busyEntryIds: StateFlow<Set<String>> = _busyEntryIds.asStateFlow()

    /** Bumped to force re-fetch (e.g. swipe-to-refresh in the UI). */
    private val refreshTick: MutableStateFlow<Int> = MutableStateFlow(0)

    init {
        screenModelScope.launchIO {
            combine(
                novelPrefs.addedRepoUrls().changes(),
                novelPrefs.installedPluginUrls().changes(),
                refreshTick,
            ) { repoUrls, installedUrls, _ -> repoUrls to installedUrls }
                .collectLatest { (repoUrls, installedUrls) ->
                    derive(repoUrls, installedUrls)
                }
        }
    }

    private suspend fun derive(repoUrls: Set<String>, installedUrls: Set<String>) {
        if (repoUrls.isEmpty()) {
            mutableState.value = State.Success(byLanguage = emptyMap(), failedRepoCount = 0)
            return
        }
        mutableState.value = State.Loading
        val (entries, failedCount) = coroutineScope {
            val fetches = repoUrls.map { url ->
                async { runCatching { installer.fetchRepo(url) } }
            }
            val results = fetches.awaitAll()
            val all = results.mapNotNull { it.getOrNull() }.flatten().distinctBy { it.url }
            val failed = results.count { it.isFailure }
            all to failed
        }
        // Canonicalize installed URLs once so the `installed` check stays cheap per row.
        // Canonical form is what [LnPluginInstaller.installFromUrl] persists, so this is the
        // form we'll find in [installedUrls].
        val installedCanonical = installedUrls
        val rows = entries.map { entry ->
            val isInstalled = canonicalizePluginUrl(entry.url) in installedCanonical
            LnPluginRow(entry = entry, installed = isInstalled)
        }
        val byLanguage = rows.groupBy { it.entry.lang.ifBlank { UNKNOWN_LANG } }
            .toSortedMap()
            .mapValues { (_, list) -> list.sortedBy { it.entry.name.lowercase() } }
        mutableState.value = State.Success(byLanguage = byLanguage, failedRepoCount = failedCount)
    }

    fun install(host: LnPluginHost, entry: LnRegistryEntry) {
        markBusy(entry.id, true)
        screenModelScope.launchIO {
            try {
                installer.installFromUrl(host, entry.url)
            } finally {
                markBusy(entry.id, false)
            }
        }
    }

    fun uninstall(entry: LnRegistryEntry) {
        // Uninstall doesn't touch the host (the manager.unregister path in
        // [LnPluginInstaller.uninstall] just drops the source registration; the loaded plugin
        // instance stays in the host's WebView until destroy). No host param needed here.
        markBusy(entry.id, true)
        screenModelScope.launchIO {
            try {
                installer.uninstall(entry.id, entry.url)
            } finally {
                markBusy(entry.id, false)
            }
        }
    }

    /** UI swipe-to-refresh hook. Re-fetches every repo (latest registry contents). */
    fun refresh() {
        refreshTick.update { it + 1 }
    }

    private fun markBusy(id: String, busy: Boolean) {
        _busyEntryIds.update { set ->
            if (busy) set + id else set - id
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            /**
             * Map of `language tag → plugins`, language-grouped and alphabetically sorted within
             * each bucket. Empty when no repos are added (the UI shows an empty-state pointer).
             */
            val byLanguage: Map<String, List<LnPluginRow>>,
            /**
             * Count of repo URLs whose fetch failed in the last derive pass. The UI surfaces this
             * once per emission via a toast so the user knows something's wrong with one of their
             * registries (typo, server down, etc.) without blocking the rest of the list.
             */
            val failedRepoCount: Int,
        ) : State {
            val isEmpty: Boolean
                get() = byLanguage.isEmpty()
        }
    }

    @Immutable
    data class LnPluginRow(
        val entry: LnRegistryEntry,
        val installed: Boolean,
    )

    companion object {
        private const val UNKNOWN_LANG = "?"
    }
}
