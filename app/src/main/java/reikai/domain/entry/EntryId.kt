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
 * The name a content entry's custom cover file is stored under in the shared `CoverCache`. Both content
 * types share one directory, so the name is namespaced by type: a manga keeps its plain row id (the
 * upstream name, unchanged on disk), a novel is prefixed. Never derive this from the id alone; a manga
 * and a novel can carry the same row id, and they would then overwrite each other's cover.
 */
fun EntryId.customCoverKey(): String = when (this) {
    is EntryId.Manga -> rawId.toString()
    is EntryId.Novel -> "novel:$rawId"
}

/**
 * The key a content entry uses in `MangaCover.vibrantCoverColorMap`, the cover-derived theme colour
 * cache. That map is upstream and keyed by [Long], so novels stay negated here rather than patching
 * Mihon for a cache that rebuilds itself on the next cover load. This is the one place the negated-id
 * projection deliberately survives, and it is safe precisely because nothing persists beyond a colour
 * that can be recomputed.
 */
fun EntryId.vibrantColorKey(): Long = when (this) {
    is EntryId.Manga -> rawId
    is EntryId.Novel -> -rawId
}
