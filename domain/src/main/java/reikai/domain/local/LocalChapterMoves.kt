package reikai.domain.local

import reikai.domain.library.ContentType

/** One chapter of a local series going to another series under another address. */
data class ChapterMove(val fromUrl: String, val toUrl: String)

/** Local series [id]'s folder renamed [fromSeries] to [toSeries], and each volume key in [volumes] (old to new). */
data class SeriesRename(val id: Long, val fromSeries: String, val toSeries: String, val volumes: Map<String, String>)

interface LocalChapterMoveRepository {
    /**
     * Moves [moves]' chapter rows from [fromOwner] to [toOwner], with what is keyed by their owner and
     * address, in one transaction: either every one moves or none does.
     */
    suspend fun move(type: ContentType, fromOwner: Long, toOwner: Long, moves: List<ChapterMove>)

    /**
     * Writes [rename] in one transaction: the series' address, its [chapters]' addresses with what is keyed by
     * them, and its volumes' saved order, labels and picked chip, which are keyed by folder and file names.
     */
    suspend fun rename(type: ContentType, rename: SeriesRename, chapters: List<ChapterMove>)
}
