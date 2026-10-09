package reikai.domain.backup

/** TrackerManager's Kitsu id; 38.sqm keys the device-side rescale on the same number. */
private const val KITSU_TRACKER_ID = 3L

/**
 * How one backup's Kitsu scores restore, decided once for the whole file. Kitsu scores were stored out of
 * 10 before moving to Kitsu's native 2-20 scale, and a restore runs no migration, so an old backup's are
 * doubled here as 38.sqm doubles the device's. A backup carrying the native-scale marker, or holding any
 * Kitsu score above 10 (which only the new scale produces), is already on it.
 */
@JvmInline
value class KitsuBackupScoreScale private constructor(private val factor: Double) {

    /** [score] as a track of [trackerId] restores it; only Kitsu's are rescaled. */
    fun restore(trackerId: Long, score: Double): Double =
        if (trackerId == KITSU_TRACKER_ID) score * factor else score

    companion object {
        /** [tracks] is every (tracker id, score) pair in the backup, manga and novels together. */
        fun of(nativeScaleMarked: Boolean, tracks: Iterable<Pair<Long, Double>>): KitsuBackupScoreScale {
            val onNativeScale = nativeScaleMarked ||
                tracks.any { (trackerId, score) -> trackerId == KITSU_TRACKER_ID && score > 10.0 }
            return KitsuBackupScoreScale(if (onNativeScale) 1.0 else 2.0)
        }
    }
}
