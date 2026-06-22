package reikai.domain.novel.model

/**
 * Partial-update patch for the `novels` table, the novel twin of
 * [tachiyomi.domain.manga.model.MangaUpdate]. Every field but [id] is nullable; null means "leave
 * unchanged" (the repo routes it through the `coalesce`-based `partialUpdate` query). Use this for
 * surgical single/multi-field writes instead of the full-row `update(Novel)`, which stays for the
 * restore / edit-info paths that legitimately write a column back to null (something `coalesce`
 * can't express).
 *
 * `genre` and `updateStrategy` are intentionally absent: SQLDelight does not preserve those custom
 * column adapters through the `coalesce` partial-update for the novels table, so patch them via the
 * full-row [reikai.domain.novel.NovelRepository.update] (Novel) instead.
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
    val editedFlags: Long? = null,
    val notes: String? = null,
    val viewerFlags: Long? = null,
)
