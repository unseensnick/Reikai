package reikai.presentation.library

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import kotlinx.coroutines.flow.StateFlow
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR

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
 * The Filter tab's axes, in manga's order, for both content types; the preferences are library-wide.
 * [updateRestrictions] is the type's own smart-update set: upstream keeps custom intervals out of stable,
 * so that axis is debug-only and follows the release-period restriction that produces it.
 */
fun libraryFilterAxes(
    libraryPreferences: LibraryPreferences,
    reikaiLibraryPreferences: ReikaiLibraryPreferences,
    updateRestrictions: Set<String>,
) = buildList {
    add(LibraryFilterAxis(MR.strings.label_downloaded, libraryPreferences.filterDownloaded, true))
    add(LibraryFilterAxis(MR.strings.action_filter_unread, libraryPreferences.filterUnread))
    add(LibraryFilterAxis(MR.strings.label_started, libraryPreferences.filterStarted))
    add(LibraryFilterAxis(MR.strings.action_filter_bookmarked, libraryPreferences.filterBookmarked))
    add(LibraryFilterAxis(MR.strings.completed, libraryPreferences.filterCompleted))
    if (!isReleaseBuildType && LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in updateRestrictions) {
        add(LibraryFilterAxis(MR.strings.action_filter_interval_custom, libraryPreferences.filterIntervalCustom))
    }
    add(LibraryFilterAxis(MR.strings.lewd, reikaiLibraryPreferences.filterLewd))
}

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
 * What a settings sheet needs from one content type, the part that genuinely differs per view. A
 * provider omits a filter axis it lacks rather than the sheet branching on content type. [filterAxes]
 * is a flow because manga's interval-custom row comes and goes with a preference, and [categories]
 * is the FULL list, since the picker must offer categories the current filters hide.
 */
data class LibraryProviderSettings(
    val filterAxes: StateFlow<List<LibraryFilterAxis>>,
    val categories: StateFlow<List<Category>>,
    /** Whether the Display tab offers the Local badge. Only manga has a local-source concept. */
    val showLocalBadge: Boolean,
)

/**
 * One view's settings sheet, described rather than rendered, so a sheet change is written once for
 * both content types. [LibraryEngine] builds it from the library-wide members, which every view
 * shares, and the view's [LibraryProviderSettings]. A null category id in [setSort] is the global scope.
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
    val showLocalBadge: Boolean,
)
