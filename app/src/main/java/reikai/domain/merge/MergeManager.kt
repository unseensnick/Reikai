package reikai.domain.merge

import kotlinx.coroutines.flow.Flow

/**
 * The merge-group operations the shared details merge actions need, so one
 * [reikai.presentation.details.EntryMergeActionHost] can drive source split / remove / reorder for either
 * content type. Both [reikai.domain.manga.MangaMergeManager] and [reikai.domain.novel.NovelMergeManager]
 * implement it over the shared [MergeGroupRepository]; the richer per-type operations (duplicate grouping,
 * series keys, unmerge, related-id resolution) deliberately stay off this interface.
 */
interface MergeManager {

    /** Persist [orderedMemberIds] as the group's source order (0 = trunk) and turn the override on. */
    suspend fun setSourceOrder(orderedMemberIds: List<Long>)

    /** Clear the per-group source-order override for [anchorId]'s group (back to the global ranking). */
    suspend fun clearSourceOrder(anchorId: Long)

    /** Split [targetIds] out of their group, dissolving it below two members; returns the survivors. */
    suspend fun removeFromGroup(relatedIds: LongArray, targetIds: List<Long>): LongArray

    /** Merge [ids] into one group, absorbing any groups they already belong to. No-op for < 2 entries. */
    suspend fun merge(ids: List<Long>)

    /** Read [anchorId]'s group before a split, so the split can be undone exactly. See [GroupSnapshot]. */
    suspend fun captureGroup(anchorId: Long): GroupSnapshot

    /** Put a group captured by [captureGroup] back as it was. */
    suspend fun restoreGroup(snapshot: GroupSnapshot)

    /** Emits on every membership change for this content type (add / remove / split / dissolve), so a
     *  details screen can refresh its group live. Backed by the group-member table, not the retired prefs. */
    fun membershipChanges(): Flow<Map<Long, Long>>
}

/**
 * A merge group as it stood, enough to put it back exactly: its members in trunk order, and whether
 * that order was the user's own rather than the global preferred-source list.
 *
 * Both facts have to be captured BEFORE the group is touched. Splitting a pair deletes the group row,
 * which takes the flag with it, so an undo that re-merges can restore the members and the order but
 * has nothing left to read the ranking from.
 */
data class GroupSnapshot(
    val orderedMemberIds: List<Long>,
    val overrideSourceRanking: Boolean,
) {
    val isEmpty: Boolean get() = orderedMemberIds.size < 2

    companion object {
        val EMPTY = GroupSnapshot(emptyList(), overrideSourceRanking = false)
    }
}
