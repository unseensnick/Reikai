package reikai.domain.entry

import reikai.domain.library.ContentType

/**
 * Neutral identity for a content entry the shared content layer drives, so shared behaviour and UI can
 * point at an entry without branching on manga-vs-novel to know what it is. The sealed shape makes a
 * mismatched (type, id) impossible to construct and gives an exhaustive `when`, the same compile-caught
 * safety the adapter seam relies on. Pairs with [ContentType], already used for filtering and grouping.
 *
 * [rawId] is the entry's own positive row id in its own table (a manga id or a novel id). The two id
 * spaces are disjoint only by this wrapper, never by sign, so never compare raw ids across types.
 */
sealed interface EntryId {
    val rawId: Long
    val contentType: ContentType

    data class Manga(override val rawId: Long) : EntryId {
        override val contentType: ContentType get() = ContentType.MANGA
    }

    data class Novel(override val rawId: Long) : EntryId {
        override val contentType: ContentType get() = ContentType.NOVELS
    }
}

/**
 * The [Long] key a content entry uses in the shared, Long-keyed cover caches (the custom-cover file in
 * `CoverCache` and `MangaCover.vibrantCoverColorMap`). Manga keep their positive row id; novels are
 * negated so a novel can never collide with a same-id manga. Centralises the negation that was spread
 * inline as `-novelId`, so the one cover-cache disguise lives behind a named projection.
 */
fun EntryId.coverCacheKey(): Long = when (this) {
    is EntryId.Manga -> rawId
    is EntryId.Novel -> -rawId
}
