package eu.kanade.tachiyomi.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class LibrarySettingsViewModel(
    val preferences: BasePreferences = Injekt.get(),
    val libraryPreferences: LibraryPreferences = Injekt.get(),
    // RK -->
    val reikaiLibraryPreferences: ReikaiLibraryPreferences = Injekt.get(),
    // RK <--
    private val setDisplayMode: SetDisplayMode = Injekt.get(),
    private val setSortModeForCategory: SetSortModeForCategory = Injekt.get(),
    trackerManager: TrackerManager = Injekt.get(),
) : ViewModel() {

    val trackersFlow = trackerManager.loggedInTrackersFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds.inWholeMilliseconds),
            initialValue = trackerManager.loggedInTrackers(),
        )

    fun toggleFilter(preference: (LibraryPreferences) -> Preference<TriState>) {
        preference(libraryPreferences).getAndSet {
            it.next()
        }
    }

    fun toggleTracker(id: Int) {
        toggleFilter { libraryPreferences.filterTracking(id) }
    }

    fun setDisplayMode(mode: LibraryDisplayMode) {
        setDisplayMode.await(mode)
    }

    fun setSort(category: Category?, mode: LibrarySort.Type, direction: LibrarySort.Direction) {
        viewModelScope.launchIO {
            setSortModeForCategory.await(category, mode, direction)
        }
    }

    // RK --> Reikai settings-sheet actions still on the Display tab. The filter, sort and group actions
    // that used to live here moved to MangaLibraryAdapter's LibrarySettingsBinding, which the one shared
    // sheet drives; upstream's own toggleFilter / toggleTracker / setSort above are unused by it but are
    // left untouched so this file keeps diffing cleanly against Mihon.

    /** Category list order (0 = manual, 1 = A->Z, 2 = Z->A). */
    fun setCategorySortOrder(value: Int) {
        reikaiLibraryPreferences.categorySortOrder.set(value)
    }

    /** Hopper long-press action (0 search .. 5 random-global; see ReikaiLibrarySettings). */
    fun setHopperLongPressAction(value: Int) {
        reikaiLibraryPreferences.hopperLongPressAction.set(value)
    }
    // RK <--
}
