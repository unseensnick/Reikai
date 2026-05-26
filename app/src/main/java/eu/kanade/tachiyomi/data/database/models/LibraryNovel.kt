package eu.kanade.tachiyomi.data.database.models

import kotlin.math.roundToInt
import yokai.data.novel.novelMapper
import yokai.domain.novel.models.Novel

data class LibraryNovel(
    val novel: Novel,
    var unread: Int = 0,
    var read: Int = 0,
    var category: Int = 0,
    var bookmarkCount: Int = 0,
    var totalChapters: Int = 0,
    var latestUpdate: Long = 0,
    var lastRead: Long = 0,
    var lastFetch: Long = 0,
) {
    val hasRead
        get() = read > 0

    companion object {
        fun mapper(
            // novel
            id: Long,
            source: String,
            url: String,
            title: String,
            author: String?,
            artist: String?,
            description: String?,
            genre: String?,
            status: Long,
            thumbnailUrl: String?,
            favorite: Boolean,
            lastUpdate: Long?,
            initialized: Boolean,
            chapterFlags: Long,
            dateAdded: Long?,
            updateStrategy: Long,
            coverLastModified: Long,
            totalPages: Long,
            lastReadAt: Long?,
            // libraryNovel
            total: Long,
            readCount: Double,
            bookmarkCount: Double,
            categoryId: Long,
            latestUpdate: Long,
            lastRead: Long,
            lastFetch: Long,
        ): LibraryNovel = LibraryNovel(
            novel = novelMapper(
                id = id,
                source = source,
                url = url,
                title = title,
                author = author,
                artist = artist,
                description = description,
                genre = genre,
                status = status,
                thumbnail_url = thumbnailUrl,
                favorite = favorite,
                last_update = lastUpdate,
                initialized = initialized,
                chapter_flags = chapterFlags,
                date_added = dateAdded,
                update_strategy = updateStrategy,
                cover_last_modified = coverLastModified,
                total_pages = totalPages,
                last_read_at = lastReadAt,
            ),
            read = readCount.roundToInt(),
            unread = maxOf((total - readCount).roundToInt(), 0),
            totalChapters = total.toInt(),
            bookmarkCount = bookmarkCount.roundToInt(),
            category = categoryId.toInt(),
            latestUpdate = latestUpdate,
            lastRead = lastRead,
            lastFetch = lastFetch,
        )
    }
}
