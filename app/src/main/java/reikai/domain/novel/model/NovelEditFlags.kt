package reikai.domain.novel.model

import reikai.data.novel.NovelStatusCode

/**
 * Bits in [Novel.editedFlags] marking which metadata fields the user overrode via Edit info. A set
 * bit means a source refresh leaves that field alone (the edit survives pull-to-refresh); clearing
 * it lets the source value win again. S1 shipped the field with author/artist/description/genres;
 * S3c adds the status bit. Title needs no bit: the source refresh never overwrites the title, so an
 * edited title persists on its own.
 */
object NovelEditFlags {
    const val AUTHOR = 1L shl 0
    const val ARTIST = 1L shl 1
    const val DESCRIPTION = 1L shl 2
    const val GENRES = 1L shl 3
    const val STATUS = 1L shl 4
}

private fun Long.has(bit: Long) = this and bit != 0L

/** Set or clear [bit] in [flags]. */
fun setEditedFlag(flags: Long, bit: Long, edited: Boolean): Long =
    if (edited) flags or bit else flags and bit.inv()

/**
 * Merge freshly [parsed] source metadata onto the [existing] stored novel, respecting edit locks:
 * a field whose [Novel.editedFlags] bit is set keeps the user's value; every other field takes the
 * source value, but a null/blank parsed value never wipes existing data (some plugins return empty
 * fields on a partial parse). Title, identity, library state, flags, and the cover are preserved
 * from [existing] (title is never refreshed; cover edits are a later stage).
 */
fun mergeRefreshedNovel(existing: Novel, parsed: Novel): Novel {
    val flags = existing.editedFlags
    return existing.copy(
        author = if (flags.has(NovelEditFlags.AUTHOR)) existing.author else parsed.author?.takeIf { it.isNotBlank() } ?: existing.author,
        artist = if (flags.has(NovelEditFlags.ARTIST)) existing.artist else parsed.artist?.takeIf { it.isNotBlank() } ?: existing.artist,
        description = if (flags.has(NovelEditFlags.DESCRIPTION)) existing.description else parsed.description?.takeIf { it.isNotBlank() } ?: existing.description,
        genre = if (flags.has(NovelEditFlags.GENRES)) existing.genre else parsed.genre?.takeIf { it.isNotEmpty() } ?: existing.genre,
        // Source UNKNOWN (0) doesn't clobber a known stored status.
        status = if (flags.has(NovelEditFlags.STATUS)) {
            existing.status
        } else {
            parsed.status.takeIf { it != NovelStatusCode.UNKNOWN.toLong() } ?: existing.status
        },
        thumbnailUrl = parsed.thumbnailUrl?.takeIf { it.isNotBlank() } ?: existing.thumbnailUrl,
        // Source-owned (no edit lock): a refresh must update the page count so newly-opened pages
        // get walked. A partial parse reporting 0 never shrinks a known count.
        totalPages = parsed.totalPages.takeIf { it > 0L } ?: existing.totalPages,
        initialized = true,
    )
}
