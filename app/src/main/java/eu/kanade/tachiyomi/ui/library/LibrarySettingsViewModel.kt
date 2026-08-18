package eu.kanade.tachiyomi.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class LibrarySettingsViewModel(
    val preferences: BasePreferences,
    val libraryPreferences: LibraryPreferences,
    // RK -->
    val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    // RK <--
    private val setDisplayMode: SetDisplayMode,
    trackerManager: TrackerManager,
) : ViewModel() {

    val trackersFlow = trackerManager.loggedInTrackersFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds.inWholeMilliseconds),
            initialValue = trackerManager.loggedInTrackers(),
        )

    fun setDisplayMode(mode: LibraryDisplayMode) {
        setDisplayMode.await(mode)
    }

    // RK --> Reikai settings-sheet actions still on the Display tab. The filter, sort and group actions
    // that used to live here moved to MangaLibraryAdapter's LibrarySettingsBinding, which the one shared
    // sheet drives, so upstream's own toggleFilter / toggleTracker / setSort went with them.

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
