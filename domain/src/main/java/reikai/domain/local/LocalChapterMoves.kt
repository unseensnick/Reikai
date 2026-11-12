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
     * Regroups local volumes without touching a file, in one transaction: the [remove] owner rows go and the [add]
     * ones are written, then every chapter of the [series] (address to id), a row's or a kept state's, read inside
     * the transaction and placed by [unitOf], goes to the one of them the table now says lists its volume, with
     * what is keyed by owner and address, its address unchanged; [volumes]' saved order and names move too.
     */
    suspend fun regroup(
        type: ContentType,
        series: Map<String, Long>,
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
     * at the new names are ones whose files are gone, and are replaced.
     */
    suspend fun rename(type: ContentType, rename: SeriesRename, movedUrl: (String) -> String)

    /**
     * Links the member folder [member] of series [rename] (whose own address does not change) to [to], in one
     * transaction: the owner rows naming [member], keeping their place in the group, then everything [rename]
     * writes, as Organize files' rows step writes it, with each address through [movedUrl].
     */
    suspend fun relinkMember(
        type: ContentType,
        rename: SeriesRename,
        member: String,
        to: String,
        movedUrl: (String) -> String,
    )

    /** The address of every local series of [type] in the database, in the library or not. */
    suspend fun localAddresses(type: ContentType): Set<String>
}
