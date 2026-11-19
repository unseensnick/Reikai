package reikai.domain.library

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.entry.EntryId
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.EntryMergeManager
import reikai.domain.novel.NovelMergeManager

/**
 * The one writer of a series' hide from the library view, for both content types: a hide or unhide
 * reaches every library member of the entry's group, so no member left out of the write can show or
 * hide the series alone. The library's verbs, the Hidden entries screen and the migration carry write
 * through here; library.md names the readers that deliberately still show a hidden series.
 */
@Inject
class HideSeries(
    private val repository: LibraryHiddenRepository,
    private val mangaMerge: MangaMergeManager,
    private val novelMerge: NovelMergeManager,
) {

    /** Every hidden entry of both types, by [EntryId], since a manga and a novel can share a row id. */
    fun subscribeAll(): Flow<Set<EntryId>> = combine(
        repository.subscribe(ContentType.MANGA),
        repository.subscribe(ContentType.NOVELS),
    ) { manga, novels -> manga.mapTo(HashSet<EntryId>()) { EntryId.Manga(it) } + novels.map { EntryId.Novel(it) } }

    suspend fun hide(ids: Collection<EntryId>) = ids.forEach { repository.hide(it.contentType, members(it)) }

    suspend fun unhide(ids: Collection<EntryId>) = ids.forEach { repository.unhide(it.contentType, members(it)) }

    /**
     * A replace migration keeps the series hidden: [from]'s hide is read before [swap] and written onto [to]
     * after it, since the target joining the library inside [swap] clears its group's hide
     * (library_hidden.sq). A target in another series' group stays as that group is, since the swap shows a
     * group it brings along. Runs inside the caller's transaction, so the hide commits with the swap.
     */
    suspend fun <T> keepAcrossSwap(from: EntryId, to: EntryId, replace: Boolean, swap: suspend () -> T): T {
        val targetGroup = mergeManager(to).captureGroup(to.rawId).orderedMemberIds
        val sameSeries = targetGroup.isEmpty() || from.rawId in targetGroup
        val carry = replace && sameSeries && repository.isHidden(from.contentType, from.rawId)
        return swap().also { if (carry) hide(listOf(to)) }
    }

    private suspend fun members(id: EntryId): List<Long> = mergeManager(id).groupLibraryMembers(id.rawId)

    private fun mergeManager(id: EntryId): EntryMergeManager = when (id) {
        is EntryId.Manga -> mangaMerge
        is EntryId.Novel -> novelMerge
    }
}
