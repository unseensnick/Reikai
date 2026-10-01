package reikai.domain.extension

import eu.kanade.tachiyomi.extension.model.Extension
import mihon.domain.extension.model.ExtensionStore

/**
 * What one apk kind's store listings can say about one installed apk, one rule for manga and novel
 * apks. Only the stores the apk can come from count ([Extension.Installed.canComeFrom]): while any of
 * them has not answered, an outage cannot be told from a store that dropped it, so it keeps what it
 * had. With all of them answered and none of the kind listed, nothing can update it. Obsolete and
 * store are only ever derived from a listing.
 */
sealed interface KindListing {
    data object Listed : KindListing

    data object Unlisted : KindListing

    data object Unknown : KindListing
}

/**
 * [available] is the kind's listings and [statuses] each store's outcome from the same fetch, keyed by
 * index URL, null before the first one; [stores] are the added stores and [storeKeys] their keys.
 */
fun Extension.Installed.kindListing(
    available: List<Extension.Available>,
    statuses: Map<String, RepoStatus>?,
    stores: List<ExtensionStore>,
    storeKeys: Set<String>,
): KindListing = when {
    statuses == null -> KindListing.Unknown
    stores.any { canComeFrom(it, storeKeys) && statuses[it.indexUrl] !is RepoStatus.Reached } -> KindListing.Unknown
    available.isNotEmpty() -> KindListing.Listed
    else -> KindListing.Unlisted
}
