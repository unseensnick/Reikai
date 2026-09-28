package reikai.domain.novel.updateerror

/**
 * A single favorited novel that failed its last update, joined with the novel's display data
 * (the `novel_update_error_view` row). Favorites only; newest first. The novel twin of
 * [reikai.domain.library.updateerror.LibraryUpdateError], but it carries [source] + [novelUrl]
 * instead of a manga id because novel details navigation is keyed by source slug + url, not by id.
 */
data class NovelUpdateError(
    val errorId: Long,
    val novelId: Long,
    val novelTitle: String,
    val source: String,
    val novelUrl: String,
    val thumbnailUrl: String?,
    val coverLastModified: Long,
    val message: String,
    val lastUpdate: Long,
)
