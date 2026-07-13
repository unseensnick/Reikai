package reikai.domain.category

/**
 * The common/mixed split for a multi-select "change categories" dialog: [common] are the category ids
 * present on every selected entry (rendered checked), [mix] are ids present on some but not all
 * (rendered indeterminate). Shared by the manga and novel library ScreenModels so the two can't drift.
 */
data class CategoryDiff(val common: Set<Long>, val mix: Set<Long>)

fun categoryDiff(perEntityCategoryIds: List<Set<Long>>): CategoryDiff {
    val common = perEntityCategoryIds.reduceOrNull { a, b -> a intersect b } ?: emptySet()
    val mix = perEntityCategoryIds.flatten().toSet() - common
    return CategoryDiff(common, mix)
}
