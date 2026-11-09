package reikai.domain.local

import reikai.domain.library.ContentType

/** One chapter of a local series going to another series under another address. */
data class ChapterMove(val fromUrl: String, val toUrl: String)

interface LocalChapterMoveRepository {
    /**
     * Moves [moves]' chapter rows from [fromOwner] to [toOwner], with what is keyed by their owner and
     * address, in one transaction: either every one moves or none does.
     */
    suspend fun move(type: ContentType, fromOwner: Long, toOwner: Long, moves: List<ChapterMove>)
}
