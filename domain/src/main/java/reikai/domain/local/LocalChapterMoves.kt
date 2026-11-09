package reikai.domain.local

import reikai.domain.library.ContentType

/** One chapter of a local series going to another series under another address. */
data class ChapterMove(val fromUrl: String, val toUrl: String)

/** A volume's saved order and name moving with its files, from one series' folder and key to another's. */
data class VolumeRowMove(
    val fromSeries: String,
    val fromKey: String,
    val toSeries: String,
    val toKey: String,
    val position: Long?,
)

/** Local series [id]'s folder renamed [fromSeries] to [toSeries], and each volume key in [volumes] (old to new). */
data class SeriesRename(val id: Long, val fromSeries: String, val toSeries: String, val volumes: Map<String, String>)

interface LocalChapterMoveRepository {
    /**
     * Moves [moves]' chapter rows from [fromOwner] to [toOwner], with what is keyed by their owner and
     * address, in one transaction: either every one moves or none does.
     */
    suspend fun move(type: ContentType, fromOwner: Long, toOwner: Long, moves: List<ChapterMove>)

    /** Moves each volume's saved order and name to its new series and key, at its new [VolumeRowMove.position]. */
    suspend fun moveVolumes(type: ContentType, moves: List<VolumeRowMove>)

    /**
     * Writes [rename] in one transaction: the series' address, its [chapters]' addresses with what is keyed by
     * them, and its volumes' saved order, labels and picked chip, which are keyed by folder and file names.
     */
    suspend fun rename(type: ContentType, rename: SeriesRename, chapters: List<ChapterMove>)
}
