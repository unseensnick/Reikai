package reikai.domain.library

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort

/**
 * Per-category sort override marker, shared by both content types. Bit 0 of a category's flags: when set
 * the category keeps its own sort (an override), otherwise it follows the global library sort. It sits
 * below Mihon's sort type/direction bits (2-6) and the Reikai hidden bit (7), so it never collides.
 *
 * Lives in the domain module (not app, like the hidden bit) because the write/reset interactors
 * (`SetSortModeForCategory`, `ResetCategoryFlags`) are Mihon domain interactors that need it.
 */
const val CATEGORY_SORT_CUSTOMIZED = 0b1L

/**
 * Whether [category] may keep [type] as a sort of its own, on read and on write. The system (Default) row
 * may keep only Manual: it is universal, one row serving the manga and novel libraries at once, so any
 * other override on it could not mean one thing for manga and another for novels, while a drag order is
 * one order over both. Sorting it any other way sets the global sort instead.
 */
fun canOverrideSort(category: Category, type: LibrarySort.Type): Boolean =
    !category.isSystemCategory || type == LibrarySort.Type.Manual

/**
 * Whether choosing [type] for [category] sets that category's own sort rather than the global one: only with
 * per-category sorts on ([categorized]) and a category that may keep [type].
 */
fun sortsOnItsOwn(category: Category?, type: LibrarySort.Type, categorized: Boolean): Boolean =
    category != null && categorized && canOverrideSort(category, type)

/** Whether [category] keeps its own sort. A stale bit on the Default row reads as no override. */
fun isSortOverridden(category: Category): Boolean =
    category.flags and CATEGORY_SORT_CUSTOMIZED != 0L &&
        canOverrideSort(category, LibrarySort.valueOf(category.flags).type)

/**
 * Bits 0 and 1 of flags written before the override bit: Mihon's per-category display mode, retired
 * upstream without clearing it, so a stale value would read as an override.
 */
const val LEGACY_DISPLAY_MODE_MASK = 0b11L

/** [flags] written before the override bit, with the retired display mode dropped. */
fun withoutLegacyDisplayMode(flags: Long): Long = flags and LEGACY_DISPLAY_MODE_MASK.inv()

/**
 * [flags] written before the override bit existed, marked as an override when their sort differs from
 * [global]: back then a category kept its own sort in its flags alone. The upgrade migration and the
 * restore of a backup from before the bit both read old flags through this.
 */
fun markLegacySortOverride(flags: Long, global: LibrarySort): Long {
    val sortOnly = withoutLegacyDisplayMode(flags)
    return if (LibrarySort.valueOf(sortOnly) != global) sortOnly or CATEGORY_SORT_CUSTOMIZED else sortOnly
}

/** The sort [category] uses: its own decoded flags when overridden, else the [global] library sort. */
fun sortForCategory(category: Category, global: LibrarySort): LibrarySort =
    if (isSortOverridden(category)) LibrarySort.valueOf(category.flags) else global

/**
 * Resolve Mihon's sort key to the neutral mode the shared comparator understands. Both libraries decode
 * their persisted flags through this one manga-layout mapping, since the novel categories folded onto the
 * shared table.
 */
fun LibrarySort.Type.toSortMode(): LibrarySortMode = when (this) {
    LibrarySort.Type.Alphabetical -> LibrarySortMode.Alphabetical
    LibrarySort.Type.LastRead -> LibrarySortMode.LastRead
    LibrarySort.Type.LastUpdate -> LibrarySortMode.LastUpdate
    LibrarySort.Type.UnreadCount -> LibrarySortMode.UnreadCount
    LibrarySort.Type.TotalChapters -> LibrarySortMode.TotalChapters
    LibrarySort.Type.LatestChapter -> LibrarySortMode.LatestChapter
    LibrarySort.Type.ChapterFetchDate -> LibrarySortMode.ChapterFetchDate
    LibrarySort.Type.DateAdded -> LibrarySortMode.DateAdded
    LibrarySort.Type.TrackerMean -> LibrarySortMode.TrackerMean
    LibrarySort.Type.Downloaded -> LibrarySortMode.Downloaded
    LibrarySort.Type.Random -> LibrarySortMode.Random
    LibrarySort.Type.Manual -> LibrarySortMode.Manual
}
