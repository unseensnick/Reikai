package reikai.domain.novel.model

/**
 * Novel twin of [tachiyomi.domain.library.model.LibraryManga]: a favorited [Novel] plus the chapter
 * counts the library needs (unread badge, download badge, sort keys). Built from the `novelLibraryView`
 * SQLDelight view. The library renders novels through the manga-shaped `LibraryItem` (see the
 * NovelLibraryItem mapper), so this model only carries the novel-side data, not the UI shape.
 */
data class LibraryNovel(
    val novel: Novel,
    val categories: List<Long>,
    val totalChapters: Long,
    val readCount: Long,
    val bookmarkCount: Long,
    val downloadCount: Long,
    val latestUpload: Long,
    val chapterFetchedAt: Long,
    /** The latest read in the novel's history, 0 when none, for the LastRead library sort. */
    val lastRead: Long,
) {
    val id: Long get() = novel.id

    val unreadCount: Long get() = totalChapters - readCount

    val hasStarted: Boolean get() = readCount > 0
}
