// RK: novel backup (ROADMAP #9). Net-new Reikai file: the light-novel twin of BackupManga, re-typed
// to the novel domain model (String source id, text-reader fields, no viewer/scanlator fields).
package eu.kanade.tachiyomi.data.backup.models

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import reikai.domain.novel.model.Novel

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
    @ProtoNumber(17) var lastReadAt: Long? = null,
    @ProtoNumber(18) var editedFlags: Long = 0,
    @ProtoNumber(19) var favorite: Boolean = true,
    @ProtoNumber(20) var chapters: List<BackupNovelChapter> = emptyList(),
    @ProtoNumber(21) var categories: List<Long> = emptyList(),
    @ProtoNumber(22) var tracking: List<BackupNovelTracking> = emptyList(),
    @ProtoNumber(23) var history: List<BackupNovelHistory> = emptyList(),
) {
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
            favorite = this@BackupNovel.favorite,
            lastUpdate = this@BackupNovel.lastUpdate,
            initialized = this@BackupNovel.initialized,
            chapterFlags = this@BackupNovel.chapterFlags,
            dateAdded = this@BackupNovel.dateAdded,
            updateStrategy = this@BackupNovel.updateStrategy,
            coverLastModified = this@BackupNovel.coverLastModified,
            totalPages = this@BackupNovel.totalPages,
            lastReadAt = this@BackupNovel.lastReadAt,
            editedFlags = this@BackupNovel.editedFlags,
        )
    }
}
