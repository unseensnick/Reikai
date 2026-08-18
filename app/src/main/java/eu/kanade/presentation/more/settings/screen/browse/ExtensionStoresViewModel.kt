package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.model.ExtensionStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ExtensionStoresViewModel(
    private val getExtensionStores: GetExtensionStores,
    private val addExtensionStore: AddExtensionStore,
    private val removeExtensionStore: RemoveExtensionStore,
    private val updateExtensionStores: UpdateExtensionStores,
    private val extensionManager: ExtensionManager,
    // RK: light-novel plugin repos live alongside the manga extension repos on this screen.
    private val novelPreferences: NovelPreferences,
) : ViewModel() {

    private val dialog = MutableStateFlow<ExtensionStoreDialog?>(null)

    val state: StateFlow<ExtensionStoreScreenState> = combine(
        getExtensionStores.subscribe(),
        // RK: fold the light-novel plugin repos (bare URLs) in next to the manga repos.
        novelPreferences.addedRepoUrls().changes(),
        dialog,
    ) { stores, lnUrls, dialog ->
        ExtensionStoreScreenState.Success(
            stores = stores,
            lnRepos = lnUrls.sorted().map(::lnRepoToStore),
            dialog = dialog,
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), ExtensionStoreScreenState.Loading)

    /**
     * Creates and adds a new repo to the database.
     *
     * @param baseUrl The baseUrl of the repo to create.
     */
    fun createRepo(baseUrl: String) {
        viewModelScope.launch {
            dialog.update {
                when (it) {
                    is ExtensionStoreDialog.Create -> it.copy(processing = true)
                    is ExtensionStoreDialog.Confirm -> it.copy(processing = true)
                    else -> it
                }
            }
            addExtensionStore(baseUrl)
                .onSuccess {
                    extensionManager.findAvailableExtensions()
                    dismissDialog()
                }
                .onFailure { throwable ->
                    dialog.update {
                        when (it) {
                            is ExtensionStoreDialog.Create -> it.copy(
                                processing = false,
                                errorMessage = throwable.message ?: "unknown error",
                            )
                            is ExtensionStoreDialog.Confirm -> it.copy(
                                processing = false,
                                errorMessage = throwable.message ?: "unknown error",
                            )
                            else -> it
                        }
                    }
                }
        }
    }

    /**
     * Refreshes information for each repository.
     */
    fun refreshRepos() {
        viewModelScope.launchIO {
            updateExtensionStores()
        }
    }

    /**
     * Deletes the given repo from the database
     */
    fun deleteRepo(baseUrl: String) {
        viewModelScope.launchIO {
            removeExtensionStore(baseUrl)
            extensionManager.findAvailableExtensions()
        }
    }

    // RK -->
    fun createLnRepo(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            novelPreferences.addedRepoUrls().set(novelPreferences.addedRepoUrls().get() + trimmed)
        }
        dismissDialog()
    }

    fun deleteLnRepo(url: String) {
        novelPreferences.addedRepoUrls().set(novelPreferences.addedRepoUrls().get() - url)
    }

    /**
     * Synthesize an [ExtensionStore] card for a light-novel repo URL so it renders identically to a
     * manga repo. GitHub raw URLs resolve to the owner name + the repo page; otherwise the host is
     * used. LN repos have no signing key, badge, or Discord contact.
     */
    private fun lnRepoToStore(url: String): ExtensionStore {
        val httpUrl = url.toHttpUrlOrNull()
        val segments = httpUrl?.pathSegments.orEmpty()
        val (name, website) = when {
            httpUrl?.host == "raw.githubusercontent.com" && segments.size >= 2 ->
                segments[0] to "https://github.com/${segments[0]}/${segments[1]}"
            httpUrl != null -> httpUrl.host to "${httpUrl.scheme}://${httpUrl.host}"
            else -> url to url
        }
        return ExtensionStore(
            indexUrl = url,
            name = name,
            badgeLabel = "",
            signingKey = "",
            contact = ExtensionStore.Contact(website = website, discord = null),
            isLegacy = false,
            // RK: a hand-added repo has no separate extension-list file; null = use the index (mihonapp/mihon#3454)
            extensionListUrl = null,
        )
    }
    // RK <--

    fun addFromDeeplink(storeIndexUrl: String) {
        viewModelScope.launchIO {
            val alreadyExists = getExtensionStores.get().any { it.indexUrl == storeIndexUrl }
            dialog.update { ExtensionStoreDialog.Confirm(url = storeIndexUrl, alreadyExists = alreadyExists) }
        }
    }

    fun showDialog(dialog: ExtensionStoreDialog) {
        this.dialog.update { dialog }
    }

    fun dismissDialog() {
        dialog.update { null }
    }
}

sealed class ExtensionStoreDialog {
    data class Create(val processing: Boolean = false, val errorMessage: String? = null) : ExtensionStoreDialog()
    data class Delete(val store: ExtensionStore) : ExtensionStoreDialog()
    data class Confirm(
        val url: String,
        val alreadyExists: Boolean = false,
        val processing: Boolean = false,
        val errorMessage: String? = null,
    ) : ExtensionStoreDialog()

    // RK: light-novel repo add/remove dialogs (write straight to NovelPreferences.addedRepoUrls).
    data object CreateLn : ExtensionStoreDialog()
    data class DeleteLn(val url: String) : ExtensionStoreDialog()
}

sealed class ExtensionStoreScreenState {

    @Immutable
    data object Loading : ExtensionStoreScreenState()

    @Immutable
    data class Success(
        val stores: List<ExtensionStore>,
        // RK: synthesized cards for the light-novel plugin repos, shown under their own header.
        val lnRepos: List<ExtensionStore> = emptyList(),
        val dialog: ExtensionStoreDialog? = null,
    ) : ExtensionStoreScreenState() {

        val isEmpty: Boolean
            get() = stores.isEmpty()
    }
}
