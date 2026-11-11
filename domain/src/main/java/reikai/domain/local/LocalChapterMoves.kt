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

/** One series' chapter rows going from [fromOwner] to [toOwner]. */
data class OwnerMoves(val fromOwner: Long, val toOwner: Long, val moves: List<ChapterMove>)

/** Local series [id]'s new address, its folder or book name. */
data class SeriesAddress(val id: Long, val url: String)

/** Local series [id]'s folder renamed [fromSeries] to [toSeries], and each volume key in [volumes] (old to new). */
data class SeriesRename(val id: Long, val fromSeries: String, val toSeries: String, val volumes: Map<String, String>)

interface LocalChapterMoveRepository {
    /**
     * Writes one move of local files in one transaction, so either all of it lands or none does: [address],
     * each of [chapters]' rows with what is keyed by their owner and address, and each of [volumes]' saved
     * order and name. A saved row already at a volume's new key is one whose files are gone, and is replaced.
     */
    suspend fun move(
        type: ContentType,
        address: SeriesAddress?,
        chapters: List<OwnerMoves>,
        volumes: List<VolumeRowMove>,
    )

    /**
     * Forgets series [series]' deleted [volumes] (key to the url prefix of its chapters) in one transaction:
     * their saved order, name and picked chip, their chapter rows and their tombstones, since the user chose
     * to delete the files and the next thing put at those names is not these chapters.
     */
    suspend fun forgetVolumes(type: ContentType, entryId: Long, series: String, volumes: Map<String, String>)

    /**
     * Writes [rename] in one transaction: the series' address, its [chapters]' addresses with what is keyed by
     * them, and its volumes' saved order, labels and picked chip, which are keyed by folder and file names.
     * Saved rows already at the new names are ones whose files are gone, and are replaced. The rename's
     * [LocalFileJournal] row, [journalId], goes in the same transaction, so a kill leaves either both or neither.
     */
    suspend fun rename(type: ContentType, rename: SeriesRename, chapters: List<ChapterMove>, journalId: Long? = null)
}
