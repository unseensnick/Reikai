package yokai.domain.novel.models

/**
 * Tracker row for a light novel. Mirrors the manga `Track` shape but lives in the novel_tracks
 * table; novel_id and manga_id don't share a key space.
 */
data class NovelTrack(
    val id: Long? = null,
    val novelId: Long,
    val syncId: Long,
    val remoteId: Long,
    val libraryId: Long? = null,
    val title: String,
    val lastChapterRead: Float = 0f,
    val totalChapters: Long = 0L,
    val status: Int = 0,
    val score: Float = 0f,
    val remoteUrl: String = "",
    val startDate: Long = 0L,
    val finishDate: Long = 0L,
)
