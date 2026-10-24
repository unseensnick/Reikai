package reikai.data.coil

import reikai.domain.entry.EntryId
import reikai.domain.entry.customCoverKey
import kotlin.time.Instant

/**
 * The key Coil caches an entry's cover under, in memory and on disk, for both content types. A custom
 * cover is keyed by its [customCoverOwner], so a manga and a novel with the same row id never share one;
 * any other cover by its address, so a browsed result and its stored row share one. A manga's key is
 * Mihon's unchanged, since [customCoverKey] is its bare id.
 */
fun coverCacheKey(customCoverOwner: EntryId?, url: String?, lastModified: Instant?): String =
    "${customCoverOwner?.customCoverKey() ?: url};$lastModified"

/**
 * The owner [coverCacheKey] keys a custom cover by, or null. Only a library entry's custom cover is
 * served, so one left behind by a removal never is.
 */
fun libraryCustomCoverOwner(entry: EntryId, isFavorite: Boolean, hasCustomCover: (EntryId) -> Boolean): EntryId? =
    entry.takeIf { isFavorite && hasCustomCover(it) }
