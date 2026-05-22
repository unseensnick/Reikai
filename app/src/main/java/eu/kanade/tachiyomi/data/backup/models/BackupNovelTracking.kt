package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import yokai.domain.novel.models.NovelTrack

/**
 * Protobuf shape for novel tracker rows in backups. Mirrors [BackupTracking] (manga side) and
 * the `novel_tracks` columns one-for-one; the only structural divergence is that this row lives
 * under a [BackupNovel] parent instead of [BackupManga].
 */
@Serializable
data class BackupNovelTracking(
    @ProtoNumber(1) var syncId: Int,
    @ProtoNumber(2) var libraryId: Long = 0L,
    @ProtoNumber(3) var mediaId: Long = 0L,
    @ProtoNumber(4) var trackingUrl: String = "",
    @ProtoNumber(5) var title: String = "",
    @ProtoNumber(6) var lastChapterRead: Float = 0F,
    @ProtoNumber(7) var totalChapters: Int = 0,
    @ProtoNumber(8) var score: Float = 0F,
    @ProtoNumber(9) var status: Int = 0,
    @ProtoNumber(10) var startedReadingDate: Long = 0,
    @ProtoNumber(11) var finishedReadingDate: Long = 0,
) {
    fun toNovelTrack(novelId: Long): NovelTrack = NovelTrack(
        id = null,
        novelId = novelId,
        syncId = syncId.toLong(),
        remoteId = mediaId,
        libraryId = libraryId.takeIf { it != 0L },
        title = title,
        lastChapterRead = lastChapterRead,
        totalChapters = totalChapters.toLong(),
        status = status,
        score = score,
        remoteUrl = trackingUrl,
        startDate = startedReadingDate,
        finishDate = finishedReadingDate,
    )

    companion object {
        fun copyFrom(track: NovelTrack): BackupNovelTracking = BackupNovelTracking(
            syncId = track.syncId.toInt(),
            libraryId = track.libraryId ?: 0L,
            mediaId = track.remoteId,
            trackingUrl = track.remoteUrl,
            title = track.title,
            lastChapterRead = track.lastChapterRead,
            totalChapters = track.totalChapters.toInt(),
            score = track.score,
            status = track.status,
            startedReadingDate = track.startDate,
            finishedReadingDate = track.finishDate,
        )
    }
}
