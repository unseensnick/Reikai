package reikai.domain.library.updateerror

/**
 * A single library entry that failed its last update, joined with the manga's display data
 * (the `library_update_error_view` row). Favorites only; newest first.
 */
data class LibraryUpdateError(
    val errorId: Long,
    val mangaId: Long,
    val mangaTitle: String,
    val sourceId: Long,
    val thumbnailUrl: String?,
    val coverLastModified: Long,
    val message: String,
    val lastUpdate: Long,
)
