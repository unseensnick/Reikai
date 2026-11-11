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

/**
 * Series [fromOwner]'s chapters in [units] (folder and volume, [VolumeOwner.WHOLE] for a whole folder) going to
 * series [toOwner], their addresses unchanged, since the files do not move.
 */
data class UnitTransfer(val fromOwner: Long, val toOwner: Long, val units: List<Pair<String, String>>)

interface LocalChapterMoveRepository {
    /**
     * Regroups local volumes without touching a file, in one transaction: each of [transfers]' chapters, a row's
     * or a kept state's, found by [unitOf] inside the transaction so one first listed since the caller planned
     * is never left behind, goes to its new owner with what is keyed by owner and address; the [remove] owner
     * rows go and the [add] ones are written; and [volumes]' saved order and names move to their new keys.
     */
    suspend fun regroup(
        type: ContentType,
        transfers: List<UnitTransfer>,
        remove: List<VolumeOwner>,
        add: List<VolumeOwner>,
        volumes: List<VolumeRowMove>,
        unitOf: (String) -> Pair<String, String>?,
    )

    /**
     * Writes the rows of an Organize files whose files have not moved yet, in one transaction: [rename] as
     * [LocalChapterMoveRepository.rename] writes it, the [remove] owner rows gone and the [add] ones written, and
     * [inTransaction] (the journal's note that the rows landed), so a kill leaves either all of it or none.
     */
    suspend fun organize(
        type: ContentType,
        rename: SeriesRename,
        remove: List<VolumeOwner>,
        add: List<VolumeOwner>,
        inTransaction: suspend () -> Unit,
        movedUrl: (String) -> String,
    )

    /**
     * Forgets series [series]' deleted [volumes] (key to the url prefix of its chapters) in one transaction:
     * their saved order, name and picked chip, and the kept state waiting under them, since the user chose to
     * delete the files and the next thing put at those names is not these chapters. Their rows are the
     * listing's to remove, as any removed file's are; the caller forgets under the prefixes again after it.
     * The [owners] rows that let the series list them go too.
     */
    suspend fun forgetVolumes(
        type: ContentType,
        entryId: Long,
        series: String,
        volumes: Map<String, String>,
        owners: List<VolumeOwner> = emptyList(),
    )

    /**
     * Writes [rename] in one transaction: the series' address, every chapter address it holds (a row's or a
     * kept state's, read inside the transaction and taken through [movedUrl]) with what is keyed by it, and its
     * volumes' saved order, labels and picked chip, which are keyed by folder and file names. Saved rows already
     * at the new names are ones whose files are gone, and are replaced. The rename's [LocalFileJournal] row,
     * [journalId], goes in the same transaction, so a kill leaves either both or neither.
     */
    suspend fun rename(type: ContentType, rename: SeriesRename, journalId: Long? = null, movedUrl: (String) -> String)
}
