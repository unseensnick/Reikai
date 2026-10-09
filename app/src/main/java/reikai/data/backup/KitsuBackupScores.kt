package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import reikai.domain.backup.KitsuBackupScoreScale

// An entry with only its tracks decoded, so the restore's first pass can see every score without
// decoding chapters. The numbers are BackupManga.tracking's and BackupNovel.tracking's, pinned by
// KitsuScoreRestoreConformanceTest.
@Serializable
private class BackupMangaTracks(@ProtoNumber(18) val tracking: List<BackupTracking> = emptyList())

@Serializable
private class BackupNovelTracks(@ProtoNumber(22) val tracking: List<BackupNovelTracking> = emptyList())

/** Each (tracker id, score) of the encoded manga entry [data]. */
fun ProtoBuf.mangaTrackScores(data: ByteArray): List<Pair<Long, Double>> =
    decodeFromByteArray(BackupMangaTracks.serializer(), data).tracking.map { it.syncId.toLong() to it.score.toDouble() }

/** Each (tracker id, score) of the encoded novel entry [data]. */
fun ProtoBuf.novelTrackScores(data: ByteArray): List<Pair<Long, Double>> =
    decodeFromByteArray(BackupNovelTracks.serializer(), data).tracking.map { it.trackerId to it.score }

fun BackupManga.rescaleKitsu(scale: KitsuBackupScoreScale): BackupManga = apply {
    tracking.forEach { it.score = scale.restore(it.syncId.toLong(), it.score.toDouble()).toFloat() }
}

fun BackupNovel.rescaleKitsu(scale: KitsuBackupScoreScale): BackupNovel = apply {
    tracking.forEach { it.score = scale.restore(it.trackerId, it.score) }
}
