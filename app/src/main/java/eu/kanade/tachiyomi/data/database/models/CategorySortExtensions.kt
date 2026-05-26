package eu.kanade.tachiyomi.data.database.models

/**
 * Apply the Reikai-fork `preferences.categorySortOrder` pref to a list of categories. Pref
 * values:
 *
 *  - `0` (default): manual — preserves the user's drag-and-drop [Category.order].
 *  - `1`: alphabetical A→Z by [Category.name] (case-insensitive).
 *  - `2`: alphabetical Z→A by [Category.name] (case-insensitive).
 *
 * Used by the "Move to / Add to categories" sheet (`SetCategoriesSheet`) so its category list
 * matches the order the user sees in the library. The library list itself, the legacy hopper
 * jump-to-category sheet, and the Compose [yokai.presentation.library.manga.MangaLibrarySectioner]
 * each apply the same comparator inline because they additionally need to pin the Default
 * category (id == 0) at the top — the move-to-categories sheet's list is user-defined
 * categories only, so it doesn't need that pin.
 *
 * Any unrecognized pref value falls back to manual order, matching the legacy comparator's
 * `else ->` branch at `LibraryPresenter.kt:781-793`.
 */
fun List<Category>.sortedByLibraryCategoryPref(pref: Int): List<Category> = when (pref) {
    1 -> sortedBy { it.name.lowercase() }
    2 -> sortedByDescending { it.name.lowercase() }
    else -> sortedBy { it.order }
}
