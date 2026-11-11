package reikai.data.chapter

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.data.local.LocalChapterFiles
import reikai.data.local.chapterVolumeUnit
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.chapter.LocalChapterStillPresentException
import reikai.domain.chapter.LocalChapterTombstones
import reikai.domain.chapter.Revived
import reikai.domain.chapter.SyncedChapter
import reikai.domain.chapter.tombstoneRevivals
import reikai.domain.chapter.unconfirmedRemovals
import reikai.domain.library.ContentType
import reikai.domain.local.LocalGroups
import reikai.domain.local.LocalVolumeOwnerRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LocalChapterTombstonesImpl(
    private val repository: ChapterTombstoneRepository,
    private val files: LocalChapterFiles,
    private val owners: LocalVolumeOwnerRepository,
) : LocalChapterTombstones {

    override suspend fun held(
        type: ContentType,
        isLocal: Boolean,
        series: String,
        removed: List<SyncedChapter>,
    ): Set<String> {
        if (!isLocal || removed.isEmpty()) return emptySet()
        val groups = LocalGroups(owners.all(type))
        if (groups.foldersOf(series).none { it != series }) return emptySet()
        val missing = groups.missingMembers(series, files.rootNames(type))
        return removed.mapNotNullTo(HashSet()) { chapter ->
            chapter.url.takeIf { chapterVolumeUnit(type, it)?.first in missing }
        }
    }

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
