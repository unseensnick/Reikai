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
 * Whether [category] may keep a sort of its own, on read and on write. The system (Default) row may not:
 * it is universal, one row serving the manga and novel libraries at once, so an override stored on it
 * could not mean one thing for manga and another for novels. Sorting it sets the global sort instead.
 */
fun canOverrideSort(category: Category): Boolean = !category.isSystemCategory

/** Whether [category] keeps its own sort. A stale bit on the Default row reads as no override. */
fun isSortOverridden(category: Category): Boolean =
    canOverrideSort(category) && category.flags and CATEGORY_SORT_CUSTOMIZED != 0L

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
}
