// RK: novel backup (ROADMAP #9). Net-new Reikai file: novel-track twin of BackupTracking. Uses the
// novel domain types directly (Long ids, Double progress/score).
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import reikai.domain.novel.model.NovelTrack

@Serializable
class BackupNovelTracking(
    @ProtoNumber(1) var trackerId: Long,
    @ProtoNumber(2) var remoteId: Long = 0,
    @ProtoNumber(3) var libraryId: Long? = null,
    @ProtoNumber(4) var title: String = "",
    @ProtoNumber(5) var lastChapterRead: Double = 0.0,
    @ProtoNumber(6) var totalChapters: Long = 0,
    @ProtoNumber(7) var status: Long = 0,
    @ProtoNumber(8) var score: Double = 0.0,
    @ProtoNumber(9) var remoteUrl: String = "",
    @ProtoNumber(10) var startDate: Long = 0,
    @ProtoNumber(11) var finishDate: Long = 0,
    @ProtoNumber(12) var private: Boolean = false,
) {
    fun toTrackImpl(novelId: Long): NovelTrack {
        return NovelTrack(
            id = -1L,
            novelId = novelId,
            trackerId = this@BackupNovelTracking.trackerId,
            remoteId = this@BackupNovelTracking.remoteId,
            libraryId = this@BackupNovelTracking.libraryId,
            title = this@BackupNovelTracking.title,
            lastChapterRead = this@BackupNovelTracking.lastChapterRead,
            totalChapters = this@BackupNovelTracking.totalChapters,
            status = this@BackupNovelTracking.status,
            score = this@BackupNovelTracking.score,
            remoteUrl = this@BackupNovelTracking.remoteUrl,
            startDate = this@BackupNovelTracking.startDate,
            finishDate = this@BackupNovelTracking.finishDate,
            private = this@BackupNovelTracking.private,
        )
    }
}
