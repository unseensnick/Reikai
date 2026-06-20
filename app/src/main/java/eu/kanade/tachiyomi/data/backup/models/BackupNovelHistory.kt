// RK: novel backup (ROADMAP #9). Net-new Reikai file: novel-history twin of BackupHistory, keyed by
// the chapter url so it re-links after restore (chapter ids change). last_read is plain epoch millis
// on the novel side (no Date adapter), so it stays a Long here.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupNovelHistory(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var lastRead: Long = 0,
    @ProtoNumber(3) var readDuration: Long = 0,
)
