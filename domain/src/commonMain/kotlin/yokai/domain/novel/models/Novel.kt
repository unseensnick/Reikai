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
)
