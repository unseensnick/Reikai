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
 * One content type's settings sheet, described rather than rendered: the provider hands over the
 * preferences it already binds and the shared sheet renders them, so a sheet change is written once for
 * manga and novels.
 *
 * Sharing the UI needs no migration because every axis is a separate preference key per content type
 * (manga `pref_filter_library_*_v2` against novel `novel_library_filter_*`, `group_library_by` against
 * `group_novel_library_by`). The binding carries each side's own [Preference], so both keep their stored
 * values. It also means a provider simply omits an axis it does not have, instead of the shared sheet
 * branching on content type.
 *
 * [filterAxes] is a flow rather than a plain list because manga's interval-custom row appears and
 * disappears with an auto-update preference the sheet does not own.
 *
 * A null category id in [setSort] is the global scope. [categories] is the provider's full category list,
 * not the filtered list the grid renders, because the picker must offer categories the current filters hide.
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
    /** Show grouped sources' icons instead of a count on a merged cover. Its own key per content type. */
    val mergeSourceIcons: Preference<Boolean>,
)
