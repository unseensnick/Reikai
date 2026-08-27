package reikai.presentation.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.update.LnPluginUpdateChecker
import tachiyomi.core.common.util.lang.launchIO

/**
 * Browse-level state shared by the Reikai Sources and Extensions tab wrappers: the sticky
 * content-type filter (one key, so both tabs stay in sync), the Browse search query, and the
 * light-novel plugin update count that feeds the Extensions tab badge. Kicks the cache-gated update
 * check on Browse open so the badge is fresh without the user opening the Novels chip.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReikaiBrowseViewModel(
    private val sourcePreferences: ReikaiSourcePreferences,
    private val novelPreferences: NovelPreferences,
    updateChecker: LnPluginUpdateChecker,
) : ViewModel() {

    val contentType: StateFlow<ContentType> = sourcePreferences.browseContentType.stateIn(viewModelScope)

    val lnUpdatesCount: StateFlow<Int> = novelPreferences.pluginUpdatesCount().stateIn(viewModelScope)

    /**
     * The Browse search bar's query. It lives here rather than on either content type's model
     * because the bar sits above the tabs and filters one list serving both.
     */
    val searchQuery: StateFlow<String?>
        field = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launchIO { updateChecker.runIfStale() }
    }

    fun setContentType(type: ContentType) {
        sourcePreferences.browseContentType.set(type)
    }

    fun search(query: String?) {
        searchQuery.update { query }
    }
}
