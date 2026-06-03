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
import yokai.domain.novel.LnInstalledPluginMetadata
import yokai.domain.novel.NovelPreferences
import yokai.novel.host.LnPluginHost
import yokai.novel.install.LnPluginInstaller
import yokai.novel.install.canonicalizePluginUrl
import yokai.novel.registry.LnRegistryEntry
import yokai.novel.update.LnPluginVersion

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
 * `install` / `uninstall` / `clearPluginData` use the app-scoped [LnPluginHost] (Koin `single`);
 * the composable no longer owns a host.
 */
class LnPluginBrowseScreenModel :
    StateScreenModel<LnPluginBrowseScreenModel.State>(State.Loading), KoinComponent {

    private val novelPrefs: NovelPreferences by inject()
    private val installer: LnPluginInstaller by inject()
    private val host: LnPluginHost by inject()

    private val _busyEntryIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    val busyEntryIds: StateFlow<Set<String>> = _busyEntryIds.asStateFlow()

    /** Per-plugin install error message, keyed by registry entry id. Cleared at the start of
     *  each install attempt so a retry-tap clears the previous error inline. */
    private val _installErrors: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    val installErrors: StateFlow<Map<String, String>> = _installErrors.asStateFlow()

    /** Bumped to force re-fetch (e.g. swipe-to-refresh in the UI). */
    private val refreshTick: MutableStateFlow<Int> = MutableStateFlow(0)

    init {
        screenModelScope.launchIO {
            // Watch installedPluginMetadata too: tapping the "Update" button on an outdated row
            // calls installer.installFromUrl which writes a new version into that map. Without
            // observing it, derive() wouldn't re-run after an update and the row would stay on
            // "Update" even after the reinstall succeeded.
            combine(
                novelPrefs.addedRepoUrls().changes(),
                novelPrefs.installedPluginUrls().changes(),
                novelPrefs.installedPluginMetadata().changes(),
                refreshTick,
            ) { repoUrls, installedUrls, metadata, _ -> Triple(repoUrls, installedUrls, metadata) }
                .collectLatest { (repoUrls, installedUrls, metadata) ->
                    derive(repoUrls, installedUrls, metadata)
                }
        }
    }

    private suspend fun derive(
        repoUrls: Set<String>,
        installedUrls: Set<String>,
        metadata: Map<String, LnInstalledPluginMetadata>,
    ) {
        if (repoUrls.isEmpty()) {
            mutableState.value = State.Success(
                installed = emptyList(),
                byLanguage = emptyMap(),
                failedRepoCount = 0,
            )
            return
        }
        // Only flip to Loading on first emission (or after clearing all repos). Subsequent
        // derives triggered by install / update keep the existing Success visible during the
        // background re-fetch so the LazyColumn doesn't unmount; otherwise it remounts and
        // loses scroll position to the top.
        if (mutableState.value !is State.Success) {
            mutableState.value = State.Loading
        }
        val (entries, failedCount) = coroutineScope {
            val fetches = repoUrls.map { url ->
                async { runCatching { installer.fetchRepo(url) } }
            }
            val results = fetches.awaitAll()
            val all = results.mapNotNull { it.getOrNull() }.flatten().distinctBy { it.url }
            val failed = results.count { it.isFailure }
            all to failed
        }
        // installedUrls is already canonical (that's what [LnPluginInstaller.installFromUrl]
        // persists), so we only need to canonicalize entry.url for the membership check.
        // `outdated` compares the version we captured at install time against what the registry
        // currently advertises; users see an "Update" button on outdated rows and tapping it
        // reinstalls (which overwrites the stored metadata version).
        val rows = entries.map { entry ->
            val canonical = canonicalizePluginUrl(entry.url)
            val isInstalled = canonical in installedUrls
            val installedVersion = metadata[canonical]?.version
            val isOutdated = isInstalled && installedVersion != null &&
                LnPluginVersion.compare(entry.version, installedVersion) > 0
            LnPluginRow(entry = entry, installed = isInstalled, outdated = isOutdated)
        }
        val installed = rows.filter { it.installed }.sortedBy { it.entry.name.lowercase() }
        val byLanguage = rows.filterNot { it.installed }
            .groupBy { it.entry.lang.ifBlank { UNKNOWN_LANG } }
            .toSortedMap()
            .mapValues { (_, list) -> list.sortedBy { it.entry.name.lowercase() } }
        mutableState.value = State.Success(
            installed = installed,
            byLanguage = byLanguage,
            failedRepoCount = failedCount,
        )

        // Keep the Browse-tab badge in sync with the live outdated count. After a reinstall,
        // this drops by one and the SharedPreferences listener in MainActivity picks up the
        // new value. Skip the write when nothing changed so we don't trip the no-op-write
        // SharedPreferences dedup (which would suppress a listener fire we don't need).
        val outdatedCount = rows.count { it.outdated }
        if (novelPrefs.pluginUpdatesCount().get() != outdatedCount) {
            novelPrefs.pluginUpdatesCount().set(outdatedCount)
        }
    }

    fun install(entry: LnRegistryEntry) {
        markBusy(entry.id, true)
        _installErrors.update { it - entry.id }
        screenModelScope.launchIO {
            try {
                installer.installFromUrl(
                    pluginJsUrl = entry.url,
                    metadata = LnInstalledPluginMetadata(
                        pluginId = entry.id,
                        iconUrl = entry.iconUrl,
                        version = entry.version,
                        lang = entry.lang,
                    ),
                )
            } catch (e: Throwable) {
                _installErrors.update { it + (entry.id to (e.message ?: e.javaClass.simpleName)) }
            } finally {
                markBusy(entry.id, false)
            }
        }
    }

    fun uninstall(entry: LnRegistryEntry) {
        // installer.uninstall drops the source registration + persistence; the host then wipes
        // the plugin's @libs/storage scope so a reinstall doesn't pick up stale login state.
        markBusy(entry.id, true)
        screenModelScope.launchIO {
            try {
                installer.uninstall(entry.id, entry.url)
                host.clearPluginStorage(entry.id)
            } finally {
                markBusy(entry.id, false)
            }
        }
    }

    /** Standalone Clear data action (overflow): wipes the plugin's @libs/storage scope without
     *  uninstalling. Re-launching the plugin starts from a fresh storage state. */
    fun clearPluginData(entry: LnRegistryEntry) {
        markBusy(entry.id, true)
        screenModelScope.launchIO {
            try {
                host.clearPluginStorage(entry.id)
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
             * Installed plugins across all added repos, alphabetical by display name. Rendered
             * above [byLanguage] under an "Installed" header. Empty until at least one plugin
             * is installed.
             */
            val installed: List<LnPluginRow>,
            /**
             * Available-to-install plugins (excludes anything in [installed]), grouped by
             * language tag and alphabetical within each bucket. Empty when no repos are added
             * or every plugin in the registries is already installed.
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
                get() = installed.isEmpty() && byLanguage.isEmpty()
        }
    }

    @Immutable
    data class LnPluginRow(
        val entry: LnRegistryEntry,
        val installed: Boolean,
        /**
         * True when [installed] and the registry's current `version` is newer than the version
         * captured at install time. Drives the "Update" button in `LnPluginBrowseContent`.
         */
        val outdated: Boolean = false,
    )

    companion object {
        private const val UNKNOWN_LANG = "?"
    }
}
