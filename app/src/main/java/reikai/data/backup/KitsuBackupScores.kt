package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import reikai.domain.backup.KitsuBackupScoreScale

// An entry with only its tracks (and a novel's source) decoded, so a pass that reads just those, the
// restore's Kitsu scores and the pre-restore check, skips the chapters. The numbers are BackupManga's
// and BackupNovel's, pinned by KitsuScoreRestoreConformanceTest and BackupFileValidatorTest.
@Serializable
private class BackupMangaTracks(@ProtoNumber(18) val tracking: List<BackupTracking> = emptyList())

@Serializable
private class BackupNovelTracks(
    @ProtoNumber(1) val source: String = "",
    @ProtoNumber(22) val tracking: List<BackupNovelTracking> = emptyList(),
)

/** Each (tracker id, score) of the encoded manga entry [data]. */
fun ProtoBuf.mangaTrackScores(data: ByteArray): List<Pair<Long, Double>> =
    decodeFromByteArray(BackupMangaTracks.serializer(), data).tracking.map { it.syncId.toLong() to it.score.toDouble() }

/** The source and every tracker id of the encoded novel entry [data]. */
fun ProtoBuf.novelSourceAndTrackerIds(data: ByteArray): Pair<String, List<Long>> {
    val novel = decodeFromByteArray(BackupNovelTracks.serializer(), data)
    return novel.source to novel.tracking.map { it.trackerId }
}

/** Each (tracker id, score) of the encoded novel entry [data]. */
fun ProtoBuf.novelTrackScores(data: ByteArray): List<Pair<Long, Double>> =
    decodeFromByteArray(BackupNovelTracks.serializer(), data).tracking.map { it.trackerId to it.score }

fun BackupManga.rescaleKitsu(scale: KitsuBackupScoreScale): BackupManga = apply {
    tracking.forEach { it.score = scale.restore(it.syncId.toLong(), it.score.toDouble()).toFloat() }
}

fun BackupNovel.rescaleKitsu(scale: KitsuBackupScoreScale): BackupNovel = apply {
    tracking.forEach { it.score = scale.restore(it.trackerId, it.score) }
}
