package reikai.domain.entry

import kotlinx.serialization.Serializable
import reikai.domain.library.ContentType

/**
 * Neutral identity for a content entry the shared content layer drives, so shared behaviour and UI can
 * point at an entry without branching on manga-vs-novel. The sealed shape makes a mismatched (type, id)
 * impossible to construct and gives an exhaustive `when`. Serializable for the routes that carry one.
 *
 * [rawId] is the entry's own positive row id in its own table (a manga id or a novel id). The two id
 * spaces are disjoint only by this wrapper, never by sign, so never compare raw ids across types.
 */
@Serializable
sealed interface EntryId {
    val rawId: Long
    val contentType: ContentType

    @Serializable
    data class Manga(override val rawId: Long) : EntryId {
        override val contentType: ContentType get() = ContentType.MANGA
    }

    @Serializable
    data class Novel(override val rawId: Long) : EntryId {
        override val contentType: ContentType get() = ContentType.NOVELS
    }

    companion object {
        /** The entry of [type] with row id [rawId]. */
        fun of(type: ContentType, rawId: Long): EntryId = if (type == ContentType.NOVELS) Novel(rawId) else Manga(rawId)
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
 * One [Long] per entry across both types, novels negated: row ids are positive, so the halves never
 * meet. For Long-keyed structures: the mixed library's Random rank, and [CoverColors],
 * which `MangaCoverMetadata` stores to preferences by this key, so the encoding is a stored format and
 * must not change. Never use it as an identity; that is what [EntryId] itself is for.
 */
fun EntryId.signedKey(): Long = when (this) {
    is EntryId.Manga -> rawId
    is EntryId.Novel -> -rawId
}
