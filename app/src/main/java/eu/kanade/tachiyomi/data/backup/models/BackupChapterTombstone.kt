// A removed local chapter's kept reading state (ChapterTombstone), carried on BackupManga and BackupNovel
// alike at 704, keyed by the chapter's url as its chapters and history are. Millis throughout; a null
// length, listened time or history duration is a row the chapter never had, a historyReadAt of 0 is
// history the user removed, as BackupHistory carries it.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.core.common.extensions.toEpochMillisOrZero
import mihon.core.common.extensions.toInstantOrNull
import reikai.domain.chapter.ChapterTombstone
import reikai.domain.chapter.TombstoneSession
import kotlin.time.Instant

@Serializable
class BackupChapterTombstone(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var chapterNumber: Double = 0.0,
    @ProtoNumber(3) var read: Boolean = false,
    @ProtoNumber(4) var bookmark: Boolean = false,
    @ProtoNumber(5) var progress: Long = 0,
    @ProtoNumber(6) var length: Long? = null,
    @ProtoNumber(7) var listenedMs: Long? = null,
    @ProtoNumber(8) var dateFetch: Long = 0,
    @ProtoNumber(9) var historyReadAt: Long = 0,
    @ProtoNumber(10) var historyDuration: Long? = null,
    @ProtoNumber(11) var sessions: List<BackupTombstoneSession> = emptyList(),
    @ProtoNumber(12) var removedAt: Long = 0,
) {
    fun toChapterTombstone() = ChapterTombstone(
        url = url,
        number = chapterNumber,
        read = read,
        bookmark = bookmark,
        progress = progress,
        length = length,
        listenedMs = listenedMs,
        dateFetch = Instant.fromEpochMilliseconds(dateFetch),
        historyReadAt = historyReadAt.toInstantOrNull(),
        historyDuration = historyDuration,
        sessions = sessions.map { TombstoneSession(it.endedAt, it.duration) },
        removedAt = removedAt,
    )
}

@Serializable
class BackupTombstoneSession(
    @ProtoNumber(1) var endedAt: Long,
    @ProtoNumber(2) var duration: Long,
)

fun ChapterTombstone.toBackup() = BackupChapterTombstone(
    url = url,
    chapterNumber = number,
    read = read,
    bookmark = bookmark,
    progress = progress,
    length = length,
    listenedMs = listenedMs,
    dateFetch = dateFetch.toEpochMilliseconds(),
    historyReadAt = historyReadAt.toEpochMillisOrZero(),
    historyDuration = historyDuration,
    sessions = sessions.map { BackupTombstoneSession(it.endedAt, it.duration) },
    removedAt = removedAt,
)
