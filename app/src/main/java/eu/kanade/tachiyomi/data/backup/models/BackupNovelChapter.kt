// RK: novel backup (Roadmap 9). Net-new Reikai file: novel-chapter twin of BackupChapter.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import reikai.domain.novel.model.NovelChapter

@Serializable
class BackupNovelChapter(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var read: Boolean = false,
    @ProtoNumber(4) var bookmark: Boolean = false,
    // lastTextProgress: hundredths scroll percent (the novel reader's resume point).
    @ProtoNumber(5) var lastTextProgress: Long = 0,
    @ProtoNumber(6) var chapterNumber: Double = 0.0,
    @ProtoNumber(7) var sourceOrder: Long = 0,
    @ProtoNumber(8) var dateFetch: Long = 0,
    @ProtoNumber(9) var dateUpload: Long = 0,
    // page: volume/section label for paged sources; empty when the source doesn't expose it.
    @ProtoNumber(10) var page: String = "",
    // No download flag is carried: the on-disk text file isn't in the backup, so downloaded state is
    // rederived from disk by NovelDownloadCache after restore.
) {
    fun toChapterImpl(novelId: Long): NovelChapter {
        return NovelChapter(
            id = -1L,
            novelId = novelId,
            url = this@BackupNovelChapter.url,
            name = this@BackupNovelChapter.name,
            read = this@BackupNovelChapter.read,
            bookmark = this@BackupNovelChapter.bookmark,
            lastTextProgress = this@BackupNovelChapter.lastTextProgress,
            chapterNumber = this@BackupNovelChapter.chapterNumber,
            sourceOrder = this@BackupNovelChapter.sourceOrder,
            dateFetch = this@BackupNovelChapter.dateFetch,
            dateUpload = this@BackupNovelChapter.dateUpload,
            page = this@BackupNovelChapter.page,
        )
    }
}
