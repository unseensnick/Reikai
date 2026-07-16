package reikai.domain.library

import tachiyomi.domain.library.model.LibrarySort

/**
 * Per-category sort override marker for the manga library, the manga twin of the novel
 * `NovelLibrarySort` CUSTOMIZED sentinel. Bit 0 of a category's flags: when set the category keeps its
 * own sort (an override), otherwise it follows the global library sort. It sits below Mihon's sort
 * type/direction bits (2-6) and the Reikai hidden bit (7), so it never collides.
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
