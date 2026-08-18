package reikai.presentation.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.interactor.GetIncognitoState
import kotlinx.coroutines.flow.StateFlow
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.update.LnPluginUpdateChecker
import tachiyomi.core.common.util.lang.launchIO

/**
 * Browse-level state shared by the Reikai Sources and Extensions tab wrappers: the sticky
 * content-type filter (one key, so both tabs stay in sync) and the light-novel plugin update count
 * that feeds the Extensions tab badge. Kicks the cache-gated update check on Browse open so the
 * badge is fresh without the user opening the Novels chip.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReikaiBrowseViewModel(
    private val sourcePreferences: ReikaiSourcePreferences,
    private val novelPreferences: NovelPreferences,
    private val getIncognitoState: GetIncognitoState,
    updateChecker: LnPluginUpdateChecker,
) : ViewModel() {

    val contentType: StateFlow<ContentType> = sourcePreferences.browseContentType.stateIn(viewModelScope)

    val lnUpdatesCount: StateFlow<Int> = novelPreferences.pluginUpdatesCount().stateIn(viewModelScope)

    init {
        viewModelScope.launchIO { updateChecker.runIfStale() }
    }

    fun setContentType(type: ContentType) {
        sourcePreferences.browseContentType.set(type)
    }

    /** Record the most recently opened LN source so the sources list's Last Used section populates.
     *  Skipped while incognito (global-only; mirrors BrowseSourceViewModel's lastUsedSource gate). */
    fun setLastUsedNovelSource(id: String) {
        if (getIncognitoState.await(null)) return
        novelPreferences.lastUsedNovelSource().set(id)
    }
}
