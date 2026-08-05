package reikai.domain.novel.model

/**
 * Partial-update patch for the `novels` table, the novel twin of
 * [tachiyomi.domain.manga.model.MangaUpdate]. Every field but [id] is nullable, null meaning leave
 * unchanged, through the repo's `coalesce`-based `partialUpdate`. The full-row `update(Novel)` stays
 * for the restore and edit-info paths that legitimately write a column back to null, which `coalesce`
 * cannot express. `genre` and `updateStrategy` are deliberately absent: SQLDelight does not preserve
 * their column adapters through that partial update, so patch them through the full-row write.
 */
data class NovelUpdate(
    val id: Long,
    val source: String? = null,
    val url: String? = null,
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val status: Long? = null,
    val thumbnailUrl: String? = null,
    val favorite: Boolean? = null,
    val lastUpdate: Long? = null,
    val initialized: Boolean? = null,
    val chapterFlags: Long? = null,
    val dateAdded: Long? = null,
    val coverLastModified: Long? = null,
    val totalPages: Long? = null,
    val lastReadAt: Long? = null,
    val notes: String? = null,
    val viewerFlags: Long? = null,
)
