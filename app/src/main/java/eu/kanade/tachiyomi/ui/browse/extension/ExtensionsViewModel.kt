package eu.kanade.tachiyomi.ui.browse.extension

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import tachiyomi.core.common.util.lang.launchIO
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ExtensionsViewModel(
    private val preferences: SourcePreferences,
    basePreferences: BasePreferences,
    private val extensionManager: ExtensionManager,
    getExtensions: GetExtensionsByType,
    // RK: the shared Extensions list tells "no stores added" from "the stores returned nothing".
    getExtensionStoreCount: GetExtensionStoreCountAsFlow,
) : ViewModel() {

    // RK -->
    // Stripped to a provider for the shared Extensions engine, which sections, searches and filters
    // the one list both content types render into. What is left is the manga data and the verbs.
    // The four lists stay as the interactor partitioned them, so the provider reads a row's section
    // off which list it came from rather than deriving that split a second time.
    val extensions: StateFlow<Extensions?> = getExtensions.subscribe()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val currentDownloads: StateFlow<Map<String, InstallStep>>
        field = MutableStateFlow<Map<String, InstallStep>>(hashMapOf())

    /** Whether an extension store is added at all, so an empty list can say which kind of empty. */
    val hasRepos: StateFlow<Boolean> = getExtensionStoreCount()
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), true)

    /** Whether installing needs the system's install-unknown-apps permission granted first. */
    val needsInstallPermission: StateFlow<Boolean> = basePreferences.extensionInstaller.changes()
        .map { it.requiresSystemPermission }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), false)
    // RK <--

    // Public so the tab badge can observe it without subscribing to the whole state.
    val updatesCount = preferences.extensionUpdatesCount.changes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), 0)

    val isRefreshing: StateFlow<Boolean>
        field = MutableStateFlow(false)

    init {
        viewModelScope.launchIO { findAvailableExtensions() }
    }

    fun installExtension(extension: Extension.Available) {
        viewModelScope.launchIO {
            extensionManager.installExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun updateExtension(extension: Extension.Installed) {
        viewModelScope.launchIO {
            extensionManager.updateExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        extensionManager.cancelInstallUpdateExtension(extension)
        removeDownloadState(extension)
    }

    private fun addDownloadState(extension: Extension, installStep: InstallStep) {
        currentDownloads.update { it + Pair(extension.pkgName, installStep) }
    }

    private fun removeDownloadState(extension: Extension) {
        currentDownloads.update { it - extension.pkgName }
    }

    private suspend fun Flow<InstallStep>.collectToInstallUpdate(extension: Extension) =
        this
            .onEach { installStep -> addDownloadState(extension, installStep) }
            .takeWhile { installStep -> installStep != InstallStep.Installed }
            .onCompletion { removeDownloadState(extension) }
            .collect()

    fun uninstallExtension(extension: Extension) {
        extensionManager.uninstallExtension(extension)
    }

    fun findAvailableExtensions() {
        viewModelScope.launchIO {
            isRefreshing.update { true }

            extensionManager.findAvailableExtensions()

            // Fake slower refresh so it doesn't seem like it's not doing anything
            delay(1.seconds)

            isRefreshing.update { false }
        }
    }

    fun trustExtension(extension: Extension.Untrusted) {
        viewModelScope.launch {
            extensionManager.trust(extension)
        }
    }

    // RK: manual lever to re-scan installed extensions and re-evaluate trust against the current
    // repos (for the rare case an extension is stuck Untrusted after its repo was added).
    fun reloadInstalledExtensions() {
        extensionManager.reloadInstalledExtensions()
    }
}

object ExtensionUiModel {
    data class Item(
        val extension: Extension,
        val installStep: InstallStep,
    )
}
