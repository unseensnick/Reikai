package eu.kanade.tachiyomi.ui.updates

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
// RK -->
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.model.toCategory
import reikai.domain.source.ReikaiSourcePreferences
// RK <--
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class UpdatesSettingsScreenModel(
    val updatesPreferences: UpdatesPreferences = Injekt.get(),
    // RK -->
    val reikaiSourcePreferences: ReikaiSourcePreferences = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getNovelCategories: GetNovelCategories = Injekt.get(),
    // RK <--
) : ScreenModel {

    fun toggleFilter(preference: (UpdatesPreferences) -> Preference<TriState>) {
        preference(updatesPreferences).getAndSet {
            it.next()
        }
    }

    // RK --> backing for the include/exclude category-filter picker. Manga and novel categories are
    // separate id spaces, so the picker shows a section per type and persists per-type selections.
    private val _mangaCategories = MutableStateFlow<List<Category>>(emptyList())
    val mangaCategories: StateFlow<List<Category>> = _mangaCategories.asStateFlow()

    private val _novelCategories = MutableStateFlow<List<Category>>(emptyList())
    val novelCategories: StateFlow<List<Category>> = _novelCategories.asStateFlow()

    init {
        screenModelScope.launchIO {
            _mangaCategories.value = getCategories.await()
            _novelCategories.value = getNovelCategories.await().map { it.toCategory() }
        }
    }

    fun setFilterCategories(enabled: Boolean) {
        reikaiSourcePreferences.updatesFilterCategories.set(enabled)
    }

    fun setMangaCategorySelections(include: Set<Long>, exclude: Set<Long>) {
        reikaiSourcePreferences.updatesFilterMangaCategoriesInclude.set(include.map(Long::toString).toSet())
        reikaiSourcePreferences.updatesFilterMangaCategoriesExclude.set(exclude.map(Long::toString).toSet())
    }

    fun setNovelCategorySelections(include: Set<Long>, exclude: Set<Long>) {
        reikaiSourcePreferences.updatesFilterNovelCategoriesInclude.set(include.map(Long::toString).toSet())
        reikaiSourcePreferences.updatesFilterNovelCategoriesExclude.set(exclude.map(Long::toString).toSet())
    }
    // RK <--
}
