package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import yokai.domain.novel.models.Novel

/**
 * Protobuf shape for a single novel in backups. Field tags mirror [BackupManga] where the column
 * has a direct analogue, so reading the .tachibk side-by-side with a manga backup is intuitive.
 *
 * Key divergences from [BackupManga]:
 *  - `source` is a String (lnreader plugin id) rather than a Long; the field tag still resolves
 *    correctly across forks because protobuf encodes the type per-field.
 *  - No `viewer` / `viewer_flags` / `excludedScanlators` (text reader has no viewer modes, and
 *    novels don't have scanlator groups).
 *  - No `history` (text reader records progress on the chapter row directly via
 *    `last_text_progress` rather than a separate history table).
 *  - `chapters` carries [BackupNovelChapter] and `tracking` carries [BackupNovelTracking];
 *    swapping the manga-side types reflects the parallel `novel_chapters` / `novel_tracks` tables.
 */
@Serializable
data class BackupNovel(
    @ProtoNumber(1) var source: String,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var artist: String? = null,
    @ProtoNumber(5) var author: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: List<String> = emptyList(),
    @ProtoNumber(8) var status: Int = 0,
    @ProtoNumber(9) var thumbnailUrl: String? = null,
    @ProtoNumber(13) var dateAdded: Long = 0,
    @ProtoNumber(16) var chapters: List<BackupNovelChapter> = emptyList(),
    @ProtoNumber(18) var tracking: List<BackupNovelTracking> = emptyList(),
    @ProtoNumber(100) var favorite: Boolean = true,
    @ProtoNumber(101) var chapterFlags: Int = 0,
    @ProtoNumber(105) var updateStrategy: Int = 0,
) {
    fun toNovel(): Novel = Novel(
        id = null,
        source = source,
        url = url,
        title = title,
        author = author,
        artist = artist,
        description = description,
        genres = genre.takeIf { it.isNotEmpty() },
        status = status,
        thumbnailUrl = thumbnailUrl,
        favorite = favorite,
        lastUpdate = 0L,
        initialized = false,
        chapterFlags = chapterFlags,
        dateAdded = dateAdded,
        updateStrategy = updateStrategy,
        coverLastModified = 0L,
    )

    companion object {
        fun copyFrom(novel: Novel): BackupNovel = BackupNovel(
            source = novel.source,
            url = novel.url,
            title = novel.title,
            artist = novel.artist,
            author = novel.author,
            description = novel.description,
            genre = novel.genres.orEmpty(),
            status = novel.status,
            thumbnailUrl = novel.thumbnailUrl,
            dateAdded = novel.dateAdded,
            favorite = novel.favorite,
            chapterFlags = novel.chapterFlags,
            updateStrategy = novel.updateStrategy,
        )
    }
}
