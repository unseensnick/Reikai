// Novel backup. Net-new Reikai file: the light-novel twin of BackupManga, type only, re-typed
// to the novel domain model (String source id, text-reader fields).
package eu.kanade.tachiyomi.data.backup.models

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.core.common.extensions.toInstantOrNull
import reikai.domain.novel.model.Novel
import tachiyomi.domain.manga.model.Manga

@Serializable
class BackupNovel(
    @ProtoNumber(1) var source: String,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var artist: String? = null,
    @ProtoNumber(5) var author: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: List<String> = emptyList(),
    @ProtoNumber(8) var status: Long = 0,
    @ProtoNumber(9) var thumbnailUrl: String? = null,
    @ProtoNumber(10) var dateAdded: Long = 0,
    @ProtoNumber(11) var lastUpdate: Long = 0,
    @ProtoNumber(12) var initialized: Boolean = false,
    @ProtoNumber(13) var chapterFlags: Long = 0,
    @ProtoNumber(14) var updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    @ProtoNumber(15) var coverLastModified: Long = 0,
    @ProtoNumber(16) var totalPages: Long = 1,
    // Read only, from backups made before the Last read sort came from history: NovelRestorer seeds a
    // history row from it for a novel that has none. Nothing writes it any more.
    @ProtoNumber(17) var lastReadAt: Long? = null,
    // Read only, from Reikai 0.1.x and 0.2.x: which of the novel's own fields above held the user's edits, by bit
    // (author, artist, description, genres, status). Edits ride in the custom fields below since.
    @ProtoNumber(18) var legacyEditedFlags: Long = 0,
    @ProtoNumber(19) var favorite: Boolean = true,
    @ProtoNumber(20) var chapters: List<BackupNovelChapter> = emptyList(),
    @ProtoNumber(21) var categories: List<Long> = emptyList(),
    @ProtoNumber(22) var tracking: List<BackupNovelTracking> = emptyList(),
    @ProtoNumber(23) var history: List<BackupNovelHistory> = emptyList(),
    @ProtoNumber(24) var notes: String = "",
    @ProtoNumber(25) var viewerFlags: Long = 0,
    // @ProtoNumber(26) var version: Long, dropped with the manga edit count (mihon c67a33f3d)
    @ProtoNumber(27) var nextUpdate: Long = 0,
    @ProtoNumber(28) var readingSessions: List<BackupReadingSession> = emptyList(),
    // A local novel's language as its files name it (local_novel_language), which a local manga keeps in
    // its memo. Null for an online novel, and in a backup made before it was carried.
    @ProtoNumber(29) var language: String? = null,
    // The entry's places in Manual library orders, on the number BackupManga uses (BackupManualOrderFields).
    @ProtoNumber(702) override var manualOrder: List<BackupManualPosition> = emptyList(),
    // A local series' volume order and names, on the number BackupManga uses (BackupLocalVolume).
    @ProtoNumber(703) override var localVolumes: List<BackupLocalVolume> = emptyList(),
    // A local series' removed chapters' kept reading state, on the number BackupManga uses (BackupChapterTombstone).
    @ProtoNumber(704) var chapterTombstones: List<BackupChapterTombstone> = emptyList(),
    // The volumes a local series lists from other folders, on the number BackupManga uses (BackupVolumeOwner).
    @ProtoNumber(705) override var volumeOwners: List<BackupVolumeOwner> = emptyList(),
    // A local series' remembered volume chip, by its key, which a restore finds again by the series' address.
    @ProtoNumber(706) override var volumePick: String? = null,
    // Hidden from the library view, on manga's number; false on an older backup, which leaves the device's hides alone.
    @ProtoNumber(708) var libraryHidden: Boolean = false,
    // The user's custom info, on the same numbers BackupManga uses (see BackupCustomInfoFields).
    @ProtoNumber(602) override var customStatus: Int = 0,
    @ProtoNumber(603) override var customThumbnailUrl: String? = null,
    @ProtoNumber(800) override var customTitle: String? = null,
    @ProtoNumber(801) override var customArtist: String? = null,
    @ProtoNumber(802) override var customAuthor: String? = null,
    @ProtoNumber(804) override var customDescription: String? = null,
    @ProtoNumber(805) override var customGenre: List<String>? = null,
    @ProtoNumber(806) override var customAltTitles: List<String> = emptyList(),
) : BackupCustomInfoFields, BackupManualOrderFields, BackupLocalVolumeFields {
    fun toNovelImpl(): Novel {
        return Novel.create().copy(
            source = this@BackupNovel.source,
            url = this@BackupNovel.url,
            title = this@BackupNovel.title,
            artist = this@BackupNovel.artist,
            author = this@BackupNovel.author,
            description = this@BackupNovel.description,
            genre = this@BackupNovel.genre.ifEmpty { null },
            status = this@BackupNovel.status,
            thumbnailUrl = this@BackupNovel.thumbnailUrl,
            // A library entry the backup knows no date for keeps the unknown date manga's restore gives
            favoriteAt = if (this@BackupNovel.favorite) {
                this@BackupNovel.dateAdded.toInstantOrNull() ?: Manga.UNKNOWN_FAVORITE_AT
            } else {
                null
            },
            lastUpdate = this@BackupNovel.lastUpdate.toInstantOrNull(),
            initialized = this@BackupNovel.initialized,
            chapterFlags = this@BackupNovel.chapterFlags,
            updateStrategy = this@BackupNovel.updateStrategy,
            coverLastModified = this@BackupNovel.coverLastModified.toInstantOrNull(),
            totalPages = this@BackupNovel.totalPages,
            notes = this@BackupNovel.notes,
            viewerFlags = this@BackupNovel.viewerFlags,
            nextUpdate = this@BackupNovel.nextUpdate.toInstantOrNull(),
        )
    }
}

/** The edits a 0.1.x or 0.2.x backup kept in the novel's own fields, as the custom info a refresh leaves alone. */
fun BackupNovel.legacyEdits(): BackupCustomInfo? {
    val flags = legacyEditedFlags
    if (flags == 0L) return null
    fun edited(bit: Int) = flags and (1L shl bit) != 0L
    return BackupCustomInfo(
        author = author.takeIf { edited(0) },
        artist = artist.takeIf { edited(1) },
        description = description.takeIf { edited(2) },
        genre = genre.takeIf { edited(3) },
        status = status.takeIf { edited(4) },
    )
}
