package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.model.ExtensionStore
// RK -->
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import reikai.domain.novel.NovelPreferences
// RK <--
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionStoresScreenModel(
    private val getExtensionStores: GetExtensionStores = Injekt.get(),
    private val addExtensionStore: AddExtensionStore = Injekt.get(),
    private val removeExtensionStore: RemoveExtensionStore = Injekt.get(),
    private val updateExtensionStores: UpdateExtensionStores = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    // RK: light-novel plugin repos live alongside the manga extension repos on this screen.
    private val novelPreferences: NovelPreferences = Injekt.get(),
) : StateScreenModel<ExtensionStoreScreenState>(ExtensionStoreScreenState.Loading) {

    private inline fun updateSuccessState(
        func: (ExtensionStoreScreenState.Success) -> ExtensionStoreScreenState.Success,
    ) {
        mutableState.update {
            when (it) {
                ExtensionStoreScreenState.Loading -> it
                is ExtensionStoreScreenState.Success -> func(it)
            }
        }
    }

    init {
        screenModelScope.launchIO {
            // RK: fold the light-novel plugin repos (bare URLs) in next to the manga repos.
            combine(
                getExtensionStores.subscribe(),
                novelPreferences.addedRepoUrls().changes(),
            ) { stores, lnUrls -> stores to lnUrls.sorted().map(::lnRepoToStore) }
                .collectLatest { (stores, lnRepos) ->
                    mutableState.update {
                        when (it) {
                            ExtensionStoreScreenState.Loading ->
                                ExtensionStoreScreenState.Success(stores = stores, lnRepos = lnRepos)
                            is ExtensionStoreScreenState.Success -> it.copy(stores = stores, lnRepos = lnRepos)
                        }
                    }
                }
        }
    }

    /**
     * Creates and adds a new repo to the database.
     *
     * @param baseUrl The baseUrl of the repo to create.
     */
    fun createRepo(baseUrl: String) {
        screenModelScope.launch {
            updateSuccessState {
                it.copy(
                    dialog = when (it.dialog) {
                        is ExtensionStoreDialog.Create -> it.dialog.copy(processing = true)
                        is ExtensionStoreDialog.Confirm -> it.dialog.copy(processing = true)
                        else -> it.dialog
                    },
                )
            }
            addExtensionStore(baseUrl)
                .onSuccess {
                    extensionManager.findAvailableExtensions()
                    dismissDialog()
                }
                .onFailure { throwable ->
                    updateSuccessState {
                        it.copy(
                            dialog = when (it.dialog) {
                                is ExtensionStoreDialog.Create -> it.dialog.copy(
                                    processing = false,
                                    errorMessage = throwable.message ?: "unknown error",
                                )
                                is ExtensionStoreDialog.Confirm -> it.dialog.copy(
                                    processing = false,
                                    errorMessage = throwable.message ?: "unknown error",
                                )
                                else -> it.dialog
                            },
                        )
                    }
                }
        }
    }

    /**
     * Refreshes information for each repository.
     */
    fun refreshRepos() {
        val status = state.value

        if (status is ExtensionStoreScreenState.Success) {
            screenModelScope.launchIO {
                updateExtensionStores()
            }
        }
    }

    /**
     * Deletes the given repo from the database
     */
    fun deleteRepo(baseUrl: String) {
        screenModelScope.launchIO {
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
        )
    }
    // RK <--

    fun addFromDeeplink(storeIndexUrl: String) {
        updateSuccessState { state ->
            state.copy(
                dialog = ExtensionStoreDialog.Confirm(
                    url = storeIndexUrl,
                    alreadyExists = state.stores.any { it.indexUrl == storeIndexUrl },
                ),
            )
        }
    }

    fun showDialog(dialog: ExtensionStoreDialog) {
        updateSuccessState { state ->
            state.copy(dialog = dialog)
        }
    }

    fun dismissDialog() {
        updateSuccessState {
            it.copy(dialog = null)
        }
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
