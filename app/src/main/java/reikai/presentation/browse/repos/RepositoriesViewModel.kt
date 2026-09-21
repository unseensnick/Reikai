package reikai.presentation.browse.repos

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.extension.interactor.UpdateExtensionStores
import reikai.domain.extension.RepoStatus
import reikai.domain.extension.toRepoStatus
import reikai.domain.novel.NovelPreferences
import reikai.novel.registry.LnRepoRegistries
import tachiyomi.core.common.util.lang.launchIO
import kotlin.time.Duration.Companion.seconds

/**
 * The Repos screen: every extension store and LN plugin repo as one list. It reads the listings the
 * rest of the app already fetched (the store lists [ExtensionManager] keeps, the registries
 * [LnRepoRegistries] keeps) and downloads only what nothing has fetched yet this session.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class RepositoriesViewModel(
    getExtensionStores: GetExtensionStores,
    private val addExtensionStore: AddExtensionStore,
    private val removeExtensionStore: RemoveExtensionStore,
    private val updateExtensionStores: UpdateExtensionStores,
    private val extensionManager: ExtensionManager,
    private val registries: LnRepoRegistries,
    prefs: NovelPreferences,
) : ViewModel() {

    private val dialog = MutableStateFlow<RepoDialog?>(null)
    private val details = MutableStateFlow<RepoCardUi?>(null)
    private val storesRefreshing = MutableStateFlow(false)

    private val pluginStatuses = registries.results
        .map<_, Map<String, RepoStatus>?> { results -> results.mapValues { it.value.toRepoStatus() } }
        .onStart { emit(null) }

    private val cards = combine(
        getExtensionStores.subscribe(),
        extensionManager.storeStatuses,
        prefs.addedRepoUrls().changes(),
        pluginStatuses,
        ::repoCards,
    )

    private val refreshing = combine(storesRefreshing, registries.isRefreshing) { stores, plugins -> stores || plugins }

    val state: StateFlow<State> = combine(cards, refreshing, dialog, details) { cards, refreshing, dialog, details ->
        State(
            isLoading = false,
            cards = cards,
            showTypeBadges = showTypeBadges(cards),
            isRefreshing = refreshing,
            dialog = dialog,
            details = details?.let { opened -> cards.find { it.address == opened.address } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    init {
        // Opened before anything listed the stores this session, e.g. from Settings on a cold start.
        if (extensionManager.storeStatuses.value == null) {
            viewModelScope.launchIO { extensionManager.findAvailableExtensions() }
        }
    }

    fun refresh() {
        viewModelScope.launchIO {
            storesRefreshing.value = true
            try {
                updateExtensionStores()
                extensionManager.findAvailableExtensions()
            } finally {
                storesRefreshing.value = false
            }
        }
        viewModelScope.launchIO { registries.refresh() }
    }

    fun showAdd(address: String? = null) {
        dialog.value = RepoDialog.Add(fromLink = address)
    }

    fun add(address: String) {
        val trimmed = address.trim()
        dialog.update { (it as? RepoDialog.Add)?.copy(processing = true, failed = false) ?: it }
        viewModelScope.launchIO {
            val added = addRepoOfEitherKind(
                addPluginRepo = { registries.add(trimmed) },
                addStore = { addExtensionStore(trimmed).onSuccess { extensionManager.findAvailableExtensions() } },
            )
            dialog.update {
                if (added) null else (it as? RepoDialog.Add)?.copy(processing = false, failed = true) ?: it
            }
        }
    }

    fun confirmRemove(card: RepoCardUi) {
        dialog.value = RepoDialog.Remove(card)
    }

    fun remove(card: RepoCardUi) {
        dialog.value = null
        details.value = null
        when (card.format) {
            RepoFormat.PLUGINS -> registries.remove(card.address)
            RepoFormat.STORE -> viewModelScope.launchIO {
                removeExtensionStore(card.address)
                extensionManager.findAvailableExtensions()
            }
        }
    }

    fun openDetails(card: RepoCardUi) {
        details.value = card
    }

    fun closeDetails() {
        details.value = null
    }

    fun dismissDialog() {
        dialog.value = null
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val cards: List<RepoCardUi> = emptyList(),
        val showTypeBadges: Boolean = false,
        val isRefreshing: Boolean = false,
        val dialog: RepoDialog? = null,
        val details: RepoCardUi? = null,
    )
}

sealed interface RepoDialog {
    /** [fromLink] is the address a deep link brought, shown read-only. */
    data class Add(
        val fromLink: String? = null,
        val processing: Boolean = false,
        val failed: Boolean = false,
    ) : RepoDialog

    data class Remove(val card: RepoCardUi) : RepoDialog
}
