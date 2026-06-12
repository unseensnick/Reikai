package reikai.presentation.browse.repo

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.novel.NovelPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Manages the light-novel plugin repo URLs ([NovelPreferences.addedRepoUrls]) for the combined repos
 * screen. LN repos are bare `plugins.min.json` URLs (no rich metadata like Mihon's `ExtensionStore`),
 * so this is a thin add/remove over the preference set.
 */
class NovelRepoStoresScreenModel(
    private val prefs: NovelPreferences = Injekt.get(),
) : ScreenModel {

    val repos: StateFlow<List<String>> = prefs.addedRepoUrls().changes()
        .map { it.sorted() }
        .stateIn(screenModelScope, SharingStarted.Eagerly, prefs.addedRepoUrls().get().sorted())

    fun addRepo(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            prefs.addedRepoUrls().set(prefs.addedRepoUrls().get() + trimmed)
        }
    }

    fun deleteRepo(url: String) {
        prefs.addedRepoUrls().set(prefs.addedRepoUrls().get() - url)
    }
}
