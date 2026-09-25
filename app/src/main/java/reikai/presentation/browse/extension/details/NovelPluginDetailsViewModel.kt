package reikai.presentation.browse.extension.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.canonicalizePluginUrl
import reikai.novel.registry.LnRepoRegistries
import reikai.novel.registry.LnRepoResult
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
import kotlin.time.Duration.Companion.seconds

/** One installed light-novel plugin, as Mihon's extension details screen shows one extension. */
@AssistedInject
class NovelPluginDetailsViewModel(
    @Assisted private val pluginId: String,
    sourceManager: NovelSourceManager,
    registries: LnRepoRegistries,
    prefs: NovelPreferences,
    private val installer: LnPluginInstaller,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(pluginId: String): NovelPluginDetailsViewModel
    }

    val state: StateFlow<State> = combine(
        flow {
            installer.ensureLoaded()
            emitAll(sourceManager.sources)
        }.map { sources -> sources.firstOrNull { it is LnPluginSource && it.id == pluginId } },
        prefs.installedPluginMetadata().changes(),
        // The page does not wait on a repo that is slow to answer; the repo line fills in when it does.
        registries.results.onStart { emit(emptyMap()) },
    ) { source, metadata, repos ->
        source?.let { novelPluginDetails(it, metadata, repos) } ?: State.Uninstalled
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State.Loading)

    fun uninstall() {
        viewModelScope.launchIO { installer.uninstall(pluginId) }
    }

    sealed interface State {
        data object Loading : State

        data object Uninstalled : State

        @Immutable
        data class Success(val plugin: NovelSource, val version: String, val repoUrl: String?) : State
    }
}

/**
 * What the details page says about [plugin]: the version its install recorded, as the Extensions row
 * shows it, and the added repo that lists its script, or null when none that answered does.
 */
internal fun novelPluginDetails(
    plugin: NovelSource,
    installed: Map<String, LnInstalledPluginMetadata>,
    repos: Map<String, LnRepoResult>,
): NovelPluginDetailsViewModel.State.Success {
    val record = installed.entries.firstOrNull { it.value.pluginId == plugin.id }
    val repoUrl = record?.key?.let { script ->
        repos.entries.firstOrNull { (_, result) ->
            (result as? LnRepoResult.Reached)?.entries.orEmpty().any { canonicalizePluginUrl(it.url) == script }
        }?.key
    }
    return NovelPluginDetailsViewModel.State.Success(plugin, record?.value?.version ?: plugin.version, repoUrl)
}
