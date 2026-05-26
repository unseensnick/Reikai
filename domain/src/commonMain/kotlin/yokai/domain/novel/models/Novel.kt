package yokai.domain.novel.models

/**
 * Domain mirror of the `novels` SQLDelight table. Held disjoint from [yokai.domain.manga.models.Manga]
 * because the source-id space differs (lnreader plugin.id is a String, not a Long) and the
 * content type is text rather than image, so several manga-only fields (viewer flags, filtered
 * scanlators, hide-title) don't apply.
 */
data class Novel(
    val id: Long?,
    val source: String,
    val url: String,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genres: List<String>?,
    val status: Int,
    val thumbnailUrl: String?,
    val favorite: Boolean,
    val lastUpdate: Long,
    val initialized: Boolean,
    val chapterFlags: Int,
    val dateAdded: Long,
    val updateStrategy: Int,
    val coverLastModified: Long,
    /**
     * For paged-novel sources (Royal Road volumes, some Japanese sources) where a single novel's
     * chapter list spans multiple endpoints. Defaults to `1` for single-page novels; the update
     * job re-fetches `oldTotalPages + 1` through this value to discover new chapters on
     * later pages.
     */
    val totalPages: Int = 1,
    /**
     * Denormalized last-read timestamp; written from the chapter-mark-read path so the
     * LastRead library sort mode doesn't pay a JOIN-per-row. Null when the novel has never
     * been opened in the reader.
     */
    val lastReadAt: Long? = null,
)
