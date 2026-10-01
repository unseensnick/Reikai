package reikai.domain.extension

import eu.kanade.tachiyomi.extension.model.Extension

/**
 * What one apk kind's store listings can say about its installed apks, one rule for manga and novel
 * apks. With none of the kind listed there is nothing to derive from: once every store answered, no
 * store lists it, so nothing can update them; after a store failed, the outage says nothing, so they
 * keep what they had. Obsolete and store are only ever derived from a listing.
 */
sealed interface KindListing {
    data class Listed(val available: List<Extension.Available>) : KindListing

    data object Unlisted : KindListing

    data object Unknown : KindListing
}

/** [statuses] is each store's outcome from the same fetch, null before the first one. */
fun kindListing(available: List<Extension.Available>, statuses: Map<String, RepoStatus>?): KindListing = when {
    available.isNotEmpty() -> KindListing.Listed(available)
    statuses != null && statuses.values.all { it is RepoStatus.Reached } -> KindListing.Unlisted
    else -> KindListing.Unknown
}
