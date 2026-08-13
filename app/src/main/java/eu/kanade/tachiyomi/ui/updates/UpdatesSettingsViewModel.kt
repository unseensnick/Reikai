package eu.kanade.tachiyomi.ui.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import reikai.domain.category.GetNovelCategories
import reikai.domain.category.RecentsSurface
import reikai.domain.category.categoryFilterPrefs
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
    // Which surface's sheet this is backing. The filter sheet is shared by every recents surface, and
    // while the combined tab is off Updates and History are two tabs, so each edits its own selection.
    private val surface: RecentsSurface = RecentsSurface.UPDATES,
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

    // The one place this sheet's surface turns into keys. Exposed rather than read through, because the
    // picker shows a stored selection even while the toggle is off, which the resolved filter clears.
    private val categoryPrefs = reikaiSourcePreferences.categoryFilterPrefs(surface)
    val filterCategories: Preference<Boolean> get() = categoryPrefs.first
    val filterCategoriesInclude: Preference<Set<String>> get() = categoryPrefs.second
    val filterCategoriesExclude: Preference<Set<String>> get() = categoryPrefs.third

    // Not surface-scoped like the category prefs: only the combined tab draws the modes this applies
    // to, so there is no second surface to hold a competing value.
    val showRead: Preference<Boolean> get() = reikaiSourcePreferences.recentsShowRead

    fun setFilterCategories(enabled: Boolean) {
        filterCategories.set(enabled)
    }

    fun setCategorySelections(include: Set<Long>, exclude: Set<Long>) {
        filterCategoriesInclude.set(include.map(Long::toString).toSet())
        filterCategoriesExclude.set(exclude.map(Long::toString).toSet())
    }

    companion object {
        val SURFACE_KEY = CreationExtras.Key<RecentsSurface>()

        val Factory = viewModelFactory {
            initializer { UpdatesSettingsViewModel(surface = this[SURFACE_KEY]!!) }
        }
    }
    // RK <--
}
