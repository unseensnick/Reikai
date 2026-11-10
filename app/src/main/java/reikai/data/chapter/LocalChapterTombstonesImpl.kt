package reikai.data.chapter

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.data.local.LocalChapterFiles
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.chapter.LocalChapterStillPresentException
import reikai.domain.chapter.LocalChapterTombstones
import reikai.domain.chapter.Revived
import reikai.domain.chapter.SyncedChapter
import reikai.domain.chapter.tombstoneRevivals
import reikai.domain.chapter.unconfirmedRemovals
import reikai.domain.library.ContentType

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LocalChapterTombstonesImpl(
    private val repository: ChapterTombstoneRepository,
    private val files: LocalChapterFiles,
) : LocalChapterTombstones {

    override suspend fun confirm(
        type: ContentType,
        isLocal: Boolean,
        removed: List<SyncedChapter>,
        listed: Collection<String>,
    ) {
        if (!isLocal || removed.isEmpty()) return
        val listings = files.listings()
        val present = unconfirmedRemovals(removed.map { it.url }, listed, { files.containerOf(type, it) }) {
            listings.present(type, it)
        }
        if (present.isNotEmpty()) throw LocalChapterStillPresentException(present)
    }

    override suspend fun <T> around(
        type: ContentType,
        ownerId: Long,
        isLocal: Boolean,
        removed: List<SyncedChapter>,
        arriving: List<SyncedChapter>,
        write: suspend () -> T,
    ): Revived<T> {
        if (!isLocal) return Revived(write(), emptySet())
        val revivals = tombstoneRevivals(arriving, repository.byOwner(type, ownerId), removed)
        val result = repository.capturing(type, ownerId, removed.map { it.id }, revivals, write)
        return Revived(result, revivals.mapTo(HashSet()) { it.url })
    }
}
