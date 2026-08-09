package reikai.presentation.browse

/** How an add ended. Nothing is written on [NeedsCategoryChoice] or [Failed]. */
sealed interface AddOutcome {
    data object Added : AddOutcome

    /** No usable default category: the caller shows its picker, whose confirm finishes the add. */
    data object NeedsCategoryChoice : AddOutcome

    /** The favorite write failed, so the entry is not in the library and nothing was filed. */
    data object Failed : AddOutcome
}

/**
 * The add-to-library order, one sequence for both content types: decide where the entry lands, then
 * favorite, then file it there. Only the order is shared; each step is the type's own verb.
 *
 * Favorite-before-file is what makes the abort meaningful: filing first and failing to favorite
 * leaves categories against a row outside the library, which no screen shows. [favorite] answers the
 * stored id, or null when its write failed; an already-favorited caller returns the id unwritten.
 */
suspend fun addEntry(
    resolveCategories: suspend () -> List<Long>?,
    favorite: suspend () -> Long?,
    fileCategories: suspend (id: Long, categoryIds: List<Long>) -> Unit,
): AddOutcome {
    val categoryIds = resolveCategories() ?: return AddOutcome.NeedsCategoryChoice
    return finishAdd(categoryIds, favorite, fileCategories)
}

/**
 * The half a category picker's confirm owes: the writes [addEntry] deferred when it had to ask. Both
 * happen here, so dismissing the picker instead of confirming adds nothing.
 */
suspend fun finishAdd(
    categoryIds: List<Long>,
    favorite: suspend () -> Long?,
    fileCategories: suspend (id: Long, categoryIds: List<Long>) -> Unit,
): AddOutcome {
    val id = favorite() ?: return AddOutcome.Failed
    fileCategories(id, categoryIds)
    return AddOutcome.Added
}
