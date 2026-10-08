package reikai.presentation.library

import eu.kanade.presentation.library.components.LibraryToolbarTitle

/**
 * The library app bar's title. The single-list view shows every category at once, so it reads
 * "Library" with the whole count, as tabs do; only the paged view without tabs, where one category
 * fills the screen, or "Always show current category" names the section.
 */
internal fun libraryToolbarTitle(
    defaultTitle: String,
    sectionLabel: String?,
    showCategoryInTitle: Boolean,
    showCategoryTabs: Boolean,
    showAllCategories: Boolean,
    showItemCounts: Boolean,
    sectionCount: () -> Int,
    wholeCount: () -> Int,
): LibraryToolbarTitle {
    if (sectionLabel == null) return LibraryToolbarTitle(defaultTitle)
    val namesSection = showCategoryInTitle || (!showCategoryTabs && !showAllCategories)
    val count = when {
        !showItemCounts -> null
        !showCategoryTabs && !showAllCategories -> sectionCount()
        showAllCategories && namesSection -> sectionCount()
        else -> wholeCount()
    }
    return LibraryToolbarTitle(if (namesSection) sectionLabel else defaultTitle, count)
}
