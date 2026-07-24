package reikai.domain.library

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
 * The sort a manga category should use: its own decoded flags when it's an override (CUSTOMIZED set),
 * else the [global] library sort.
 */
fun sortForCategory(flags: Long, global: LibrarySort): LibrarySort =
    if (flags and CATEGORY_SORT_CUSTOMIZED != 0L) LibrarySort.valueOf(flags) else global

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
