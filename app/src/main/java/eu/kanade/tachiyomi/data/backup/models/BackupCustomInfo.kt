// Net-new. The custom-info fields BackupManga and BackupNovel carry per entry, and the fold that
// reads an older Reikai backup's root lists (Backup 713 and 714) into them, so restore reads one place.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Numbered as Komikku and Yōkai number them on their BackupManga (803 is skipped in both), so custom
 * info restores across the three apps. Yōkai has no 603, so it drops only the cover URL. BackupNovel
 * reuses the numbers, which gives both content types one mapping.
 */
interface BackupCustomInfoFields {
    var customStatus: Int
    var customThumbnailUrl: String?
    var customTitle: String?
    var customArtist: String?
    var customAuthor: String?
    var customDescription: String?
    var customGenre: List<String>?
}

/** One entry's custom info, a null field meaning no override. */
data class BackupCustomInfo(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Long? = null,
    val thumbnailUrl: String? = null,
)

/**
 * Null when the entry carries no custom info. Status 0 is Unknown, which the editor never stores as an
 * override, so it doubles as "no override" the way the forks read it, and Yokai writes -1 for the same;
 * an empty genre list is no override.
 */
var BackupCustomInfoFields.customInfo: BackupCustomInfo?
    get() = BackupCustomInfo(
        title = customTitle,
        author = customAuthor,
        artist = customArtist,
        description = customDescription,
        genre = customGenre?.takeIf { it.isNotEmpty() },
        status = customStatus.takeIf { it > 0 }?.toLong(),
        thumbnailUrl = customThumbnailUrl,
    ).takeUnless { it == BackupCustomInfo() }
    set(value) {
        customTitle = value?.title
        customAuthor = value?.author
        customArtist = value?.artist
        customDescription = value?.description
        customGenre = value?.genre
        customStatus = value?.status?.toInt() ?: 0
        customThumbnailUrl = value?.thumbnailUrl
    }

/**
 * Reikai 0.3.x wrote custom info as root lists keyed by {source, url} instead of on each entry. This
 * folds such an entry onto the one it names as the entry is decoded. An entry that already carries its
 * own custom info keeps it, so a backup holding both layouts reads as the newer one. 0.3.x also listed
 * novels out of the library, which no entry claims; those are left for the device's own rows.
 */
class LegacyCustomInfo(
    manga: List<BackupCustomMangaInfo>,
    novels: List<BackupCustomNovelInfo>,
) {
    private val mangaByRef = manga.associate { (it.source to it.url) to it.toCustomInfo() }
    private val novelsByRef = novels.associate { (it.source to it.url) to it.toCustomInfo() }
    private val claimedManga = HashSet<Pair<Long, String>>()
    private val claimedNovels = HashSet<Pair<String, String>>()

    fun decodeManga(parser: ProtoBuf, bytes: ByteArray): BackupManga =
        applyTo(parser.decodeFromByteArray(BackupManga.serializer(), bytes))

    fun decodeNovel(parser: ProtoBuf, bytes: ByteArray): BackupNovel =
        applyTo(parser.decodeFromByteArray(BackupNovel.serializer(), bytes))

    fun applyTo(manga: BackupManga): BackupManga = manga.apply {
        claimedManga += source to url
        fold(mangaByRef[source to url])
    }

    fun applyTo(novel: BackupNovel): BackupNovel = novel.apply {
        claimedNovels += source to url
        fold(novelsByRef[source to url])
    }

    /** Root-list rows no decoded entry named, keyed by (source, url); read once every entry is decoded. */
    fun unclaimedManga(): Map<Pair<Long, String>, BackupCustomInfo> = mangaByRef - claimedManga

    fun unclaimedNovels(): Map<Pair<String, String>, BackupCustomInfo> = novelsByRef - claimedNovels

    private fun BackupCustomInfoFields.fold(legacy: BackupCustomInfo?) {
        if (legacy != null && customInfo == null) customInfo = legacy
    }
}

private fun BackupCustomMangaInfo.toCustomInfo() =
    BackupCustomInfo(title, author, artist, description, genre.takeIf { it.isNotEmpty() }, status, thumbnailUrl)

private fun BackupCustomNovelInfo.toCustomInfo() =
    BackupCustomInfo(title, author, artist, description, genre.takeIf { it.isNotEmpty() }, status, thumbnailUrl)
