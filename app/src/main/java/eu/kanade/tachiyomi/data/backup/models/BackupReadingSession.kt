// One reading session, carried on BackupManga and BackupNovel alike: keyed by the chapter url so it
// re-links after restore (chapter ids change). endedAt is epoch millis, duration is ms.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import reikai.domain.readinglog.UrlReadingSession
import kotlin.time.Instant

@Serializable
class BackupReadingSession(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var endedAt: Long,
    @ProtoNumber(3) var duration: Long,
)

fun UrlReadingSession.toBackupReadingSession() =
    BackupReadingSession(url = chapterUrl, endedAt = endedAt.toEpochMilliseconds(), duration = duration)

fun BackupReadingSession.toUrlReadingSession() =
    UrlReadingSession(chapterUrl = url, endedAt = Instant.fromEpochMilliseconds(endedAt), duration = duration)
