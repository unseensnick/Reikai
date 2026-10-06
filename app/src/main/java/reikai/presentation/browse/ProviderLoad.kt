package reikai.presentation.browse

/**
 * How far the providers behind one multi-provider Browse list have answered, where a null answer is one
 * still loading. The list loads only while nothing has answered, so a slow half (a plugin repo on the
 * network) never holds back the half that is ready.
 */
data class ProviderLoad(val isLoading: Boolean, val hasPending: Boolean) {
    companion object {
        /** [answers] holds one entry per provider the chip shows, never one it hides. */
        fun of(answers: List<Any?>) = ProviderLoad(
            isLoading = answers.all { it == null },
            hasPending = answers.any { it == null },
        )
    }
}

/** A multi-provider Browse list, which may be showing part of itself while a provider is out. */
interface ProviderList {
    val items: List<Any?>

    /** A provider has not answered yet, so the list is showing part of itself. */
    val hasPending: Boolean

    // A half still on its way must not read as "nothing found".
    val isEmpty: Boolean get() = items.isEmpty() && !hasPending
}
