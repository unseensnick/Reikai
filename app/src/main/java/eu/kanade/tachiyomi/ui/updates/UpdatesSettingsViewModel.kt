package eu.kanade.tachiyomi.ui.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import reikai.domain.category.GetNovelCategories
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class UpdatesSettingsViewModel(
    val updatesPreferences: UpdatesPreferences = Injekt.get(),
    // RK -->
    val reikaiSourcePreferences: ReikaiSourcePreferences = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getNovelCategories: GetNovelCategories = Injekt.get(),
    // RK <--
) : ViewModel() {

    fun toggleFilter(preference: (UpdatesPreferences) -> Preference<TriState>) {
        preference(updatesPreferences).getAndSet {
            it.next()
        }
    }

    // RK --> backing for the include/exclude category-filter picker. One selection over the whole
    // category table, so the picker lists manga-visible and novel-visible rows together, deduped
    // (a universal row is in both queries) and in table order.
    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    init {
        viewModelScope.launchIO {
            _categories.value = (getCategories.await() + getNovelCategories.await())
                .distinctBy { it.id }
                .sortedBy { it.order }
        }
    }

    // This sheet belongs to the Updates tab, so it edits that surface's selection. History and the
    // combined Recents tab carry their own; see RecentsSurface.
    fun setFilterCategories(enabled: Boolean) {
        reikaiSourcePreferences.updatesFilterCategories.set(enabled)
    }

    fun setCategorySelections(include: Set<Long>, exclude: Set<Long>) {
        reikaiSourcePreferences.updatesFilterCategoriesInclude.set(include.map(Long::toString).toSet())
        reikaiSourcePreferences.updatesFilterCategoriesExclude.set(exclude.map(Long::toString).toSet())
    }
    // RK <--
}
