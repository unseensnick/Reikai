package reikai.presentation.browse

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.interactor.GetIncognitoState
import kotlinx.coroutines.flow.StateFlow
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.update.LnPluginUpdateChecker
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Browse-level state shared by the Reikai Sources and Extensions tab wrappers: the sticky
 * content-type filter (one key, so both tabs stay in sync) and the light-novel plugin update count
 * that feeds the Extensions tab badge. Kicks the cache-gated update check on Browse open so the
 * badge is fresh without the user opening the Novels chip.
 */
class ReikaiBrowseScreenModel(
    private val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
    private val novelPreferences: NovelPreferences = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    updateChecker: LnPluginUpdateChecker = Injekt.get(),
) : ScreenModel {

    val contentType: StateFlow<ContentType> = sourcePreferences.browseContentType.stateIn(screenModelScope)

    val lnUpdatesCount: StateFlow<Int> = novelPreferences.pluginUpdatesCount().stateIn(screenModelScope)

    init {
        screenModelScope.launchIO { updateChecker.runIfStale() }
    }

    fun setContentType(type: ContentType) {
        sourcePreferences.browseContentType.set(type)
    }

    /** Record the most recently opened LN source so the sources list's Last Used section populates.
     *  Skipped while incognito (global-only; mirrors BrowseSourceScreenModel's lastUsedSource gate). */
    fun setLastUsedNovelSource(id: String) {
        if (getIncognitoState.await(null)) return
        novelPreferences.lastUsedNovelSource().set(id)
    }
}
