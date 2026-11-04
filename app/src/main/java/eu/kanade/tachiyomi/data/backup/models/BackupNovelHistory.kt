// Novel backup. Net-new Reikai file: novel-history twin of BackupHistory, type only, keyed by
// the chapter url so it re-links after restore (chapter ids change). lastRead is epoch millis, 0 for
// history the user removed, as BackupHistory carries it.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupNovelHistory(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var lastRead: Long = 0,
    @ProtoNumber(3) var readDuration: Long = 0,
    // The part of readDuration spent read aloud, which keeps a listened chapter from teaching the reading pace.
    @ProtoNumber(4) var listenedMs: Long = 0,
)
