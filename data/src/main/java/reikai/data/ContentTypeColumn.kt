package reikai.data

import reikai.domain.library.ContentType
import reikai.domain.library.StoredType

// The stored `content_type` of merge_group, the dedupe_merged tables and the local volume tables, which 50.sqm
// and 51.sqm also write. Mapped by constant, never by ordinal, since ContentType.ALL sits first and would shift it.
private const val DB_MANGA = 0L
private const val DB_NOVEL = 1L

internal fun ContentType.toDbValue(): Long = when (this) {
    ContentType.MANGA -> DB_MANGA
    ContentType.NOVELS -> DB_NOVEL
    ContentType.ALL -> error("ContentType.ALL has no stored content_type")
}

internal fun StoredType.toDbValue(): Long = when (this) {
    StoredType.MANGA -> DB_MANGA
    StoredType.NOVELS -> DB_NOVEL
}

internal fun Long.toContentType(): ContentType = when (this) {
    DB_MANGA -> ContentType.MANGA
    DB_NOVEL -> ContentType.NOVELS
    else -> error("Unknown stored content_type $this")
}

internal fun Long.toStoredType(): StoredType = when (this) {
    DB_MANGA -> StoredType.MANGA
    DB_NOVEL -> StoredType.NOVELS
    else -> error("Unknown stored content_type $this")
}
