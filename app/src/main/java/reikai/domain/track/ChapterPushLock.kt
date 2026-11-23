package reikai.domain.track

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import reikai.domain.entry.EntryId
import java.util.concurrent.ConcurrentHashMap

/**
 * One chapter push at a time per merge group, from the tracker refresh to the saved row. Two pushes of one
 * chapter (the last page reached twice before the first push lands) both read the old progress and both
 * send it; the second now refreshes after the first landed and skips. [group] is every entry whose tracks
 * the push may write, so a reader push on one copy and a queued retry of a sibling's row share a lock.
 * From mihonapp/mihon#4306, which locks per manga.
 */
object ChapterPushLock {
    private val locks = ConcurrentHashMap<EntryId, Mutex>()

    suspend fun <T> around(group: List<EntryId>, push: suspend () -> T): T =
        locks.computeIfAbsent(group.minBy { it.rawId }) { Mutex() }.withLock { push() }
}
