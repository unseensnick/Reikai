package reikai.presentation.library

import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort

/**
 * One tri-state row on the settings sheet's Filter tab.
 *
 * [lockedByDownloadedOnly] marks the axis that the global "Downloaded only" mode forces on and disables.
 * Only the downloaded axis sets it, and both content types read that mode from the same shared preference,
 * so the shared sheet applies the lock rather than each provider describing it.
 */
data class LibraryFilterAxis(
    val labelRes: StringResource,
    val preference: Preference<TriState>,
    val lockedByDownloadedOnly: Boolean = false,
)

/**
 * The include/exclude category filter's own preferences. The category list it picks from is
 * [LibrarySettingsBinding.categories], since the sort scope needs that same list.
 */
data class LibraryCategoryFilter(
    val enabled: Preference<Boolean>,
    val included: Preference<Set<String>>,
    val excluded: Preference<Set<String>>,
)

/**
 * One view's settings sheet, described rather than rendered, so a sheet change is written once for
 * both content types. Every filter axis now writes ONE library-wide preference, leaving the binding
 * for what genuinely differs per view; a provider omits an axis it lacks rather than the sheet
 * branching on content type. [filterAxes] is a flow because manga's interval-custom row comes and goes
 * with a preference. A null category id in [setSort] is the global scope, and [categories] is the FULL
 * list, since the picker must offer categories the current filters hide.
 */
data class LibrarySettingsBinding(
    val filterAxes: StateFlow<List<LibraryFilterAxis>>,
    val trackerFilter: (trackerId: Int) -> Preference<TriState>,
    val categoryFilter: LibraryCategoryFilter,
    val categories: StateFlow<List<Category>>,
    val groupMode: Preference<Int>,
    val globalSort: StateFlow<LibrarySort>,
    val setSort: (categoryId: Long?, type: LibrarySort.Type, direction: LibrarySort.Direction) -> Unit,
    val resetSort: (categoryId: Long) -> Unit,
    /** Whether the Display tab offers the Local badge. Only manga has a local-source concept. */
    val showLocalBadge: Boolean,
    /** Show grouped sources' icons instead of a count on a merged cover. One shared key; both
     *  adapters pass [reikai.domain.library.ReikaiLibraryPreferences.showMergeSourceIcons]. */
    val mergeSourceIcons: Preference<Boolean>,
)
