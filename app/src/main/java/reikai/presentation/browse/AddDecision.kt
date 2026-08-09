package reikai.presentation.browse

/**
 * What a long-press on a browse result should do. [T] is the content type's own duplicate payload,
 * which stays per type until the two duplicate dialogs collapse into one.
 */
sealed interface AddDecision<out T> {
    /** Already in the library: offer to take it back out. */
    data object Remove : AddDecision<Nothing>

    /** Something similar is already there: ask before adding. */
    data class ConfirmDuplicate<T>(val duplicates: T) : AddDecision<T>

    data object Add : AddDecision<Nothing>
}

/**
 * The long-press rule, written once for both content types: an entry already in the library offers
 * removal, a possible duplicate asks first, anything else adds. [findDuplicates] answers null when
 * there is none, and is not called at all for an entry already in the library, which is the only
 * branch that needs no lookup.
 */
suspend fun <T : Any> decideAdd(
    inLibrary: Boolean,
    findDuplicates: suspend () -> T?,
): AddDecision<T> = when {
    inLibrary -> AddDecision.Remove
    else -> findDuplicates()?.let { AddDecision.ConfirmDuplicate(it) } ?: AddDecision.Add
}
