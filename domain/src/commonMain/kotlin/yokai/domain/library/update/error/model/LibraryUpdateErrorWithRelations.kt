package yokai.domain.library.update.error.model

/**
 * One library-update failure joined with its manga, as read from `library_update_error_view`.
 * Drives the update-error screen (cover + title + source + the error message).
 */
data class LibraryUpdateErrorWithRelations(
    val mangaId: Long,
    val mangaTitle: String,
    val mangaSource: Long,
    val thumbnailUrl: String?,
    val coverLastModified: Long,
    val errorId: Long,
    val message: String,
    val lastUpdate: Long,
)
