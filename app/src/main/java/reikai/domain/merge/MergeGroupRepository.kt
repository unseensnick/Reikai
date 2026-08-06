package reikai.domain.merge

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ContentType
import reikai.domain.merge.model.MergeGroup

/**
 * Storage for persisted merge groups, the live resolution path for both content types. Parameterized by
 * [ContentType]; the manga and novel members live in separate tables (they FK-cascade to their own entry
 * table), but the group identity and this API are shared. [ContentType.ALL] is not a valid group type.
 */
interface MergeGroupRepository {

    suspend fun getGroup(groupId: Long): MergeGroup?

    /** The group [entryId] belongs to, or null if it is in none. */
    suspend fun getGroupId(contentType: ContentType, entryId: Long): Long?

    /**
     * Member entry ids of [groupId], ordered by source priority then insertion order. The WHOLE group,
     * library or not, so a data operation (the split undo, a backup, a rewrite) sees all of it.
     */
    suspend fun getMembers(contentType: ContentType, groupId: Long): List<Long>

    /**
     * [getMembers] restricted to members still in the library, same order. What every read that
     * displays or aggregates a group asks for: removing an entry from the library preserves its group
     * (so a re-add rejoins it) but must stop it feeding the chapter list, the counts and the chips.
     */
    suspend fun getFavoriteMembers(contentType: ContentType, groupId: Long): List<Long>

    /**
     * Create a group over [entryIds] and return its id. Returns null for fewer than two distinct ids
     * (a group of one is meaningless). New members start at the default source priority, so a fresh
     * group falls back to the global preferred-source ranking until it explicitly overrides.
     */
    suspend fun createGroup(contentType: ContentType, entryIds: List<Long>): Long?

    /** Remove [entryIds] from whatever group each is in. */
    suspend fun removeMembers(contentType: ContentType, entryIds: List<Long>)

    /** Delete the group row; its member rows cascade away. */
    suspend fun dissolveGroup(groupId: Long)

    /** All memberships of [contentType] as entry-id -> group-id, for batch reads (collapse, group-by-series). */
    suspend fun getAllMemberships(contentType: ContentType): Map<Long, Long>

    /** Reactive [getAllMemberships]: re-emits whenever the memberships of [contentType] change, so the
     *  library re-collapses when a group is created, split, or dissolved. */
    fun getAllMembershipsAsFlow(contentType: ContentType): Flow<Map<Long, Long>>

    /**
     * Per-group source-ranking overrides (group id -> member ids in trunk order), only for groups whose
     * override is on. Reactive: re-emits when a group is reordered or its override cleared, so the library
     * re-collapses onto the new trunk. Groups without an override are absent, so the collapse falls back to
     * the global preferred-source list for those. This is the library-wide, chapter-free equivalent of the
     * per-group [reikai.domain.merge.EntryMergeManager.overrideRankingMemberIds].
     */
    fun getOverrideRankingsAsFlow(contentType: ContentType): Flow<Map<Long, List<Long>>>

    /**
     * Merge [ids] into one group, absorbing any groups they already belong to (so merging two
     * collapsed cards pulls in every hidden member). Atomic. Returns the group id, or null when fewer
     * than two distinct entries would take part.
     * The group of the first id that has one SURVIVES, keeping its id, member order and ranking;
     * arrivals are appended after its members, and an absorbed group's own ranking does not follow it
     * across. To state a whole group outright, order and flag together, use [materializeGroup].
     */
    suspend fun merge(contentType: ContentType, ids: List<Long>): Long?

    /**
     * Replace whatever grouping [orderedMemberIds] currently have with exactly one group: these
     * members, in this order, with this [overrideSourceRanking]. Atomic. Returns the group id, or
     * null for fewer than two distinct ids.
     * The counterpart to [merge] for a caller that knows the whole answer (undoing a source split,
     * materializing a backed-up group). [merge] cannot serve: it folds in members it was not given
     * and cannot restore a flag whose group row is already gone.
     */
    suspend fun materializeGroup(
        contentType: ContentType,
        orderedMemberIds: List<Long>,
        overrideSourceRanking: Boolean,
    ): Long?

    /**
     * Remove [targetIds] from their group and return the surviving members. If the removal leaves fewer
     * than two members, the group is dissolved (the lone survivor becomes standalone). Atomic.
     */
    suspend fun removeFromGroup(contentType: ContentType, targetIds: List<Long>): List<Long>

    /**
     * Atomically swap [oldId] out of its group and [newId] in (a replace migration). ONE transaction:
     * remove-then-merge left a window where a cancellation stranded the group half-swapped and a
     * retry, seeing [oldId] already ungrouped, skipped the join. No-op when [oldId] is ungrouped, so
     * a retry after full success is harmless. A group left under two members is dissolved.
     * [onDissolve] runs inside the transaction with the members as they stood, only for the case that
     * really breaks a group up: migrating onto a sibling. Why: [EntryMergeManager].
     */
    suspend fun replaceInGroup(
        contentType: ContentType,
        oldId: Long,
        newId: Long,
        onDissolve: suspend (memberIds: List<Long>) -> Unit = {},
    )

    /** Dissolve the group [entryId] belongs to, if any (every member becomes standalone). */
    suspend fun dissolve(contentType: ContentType, entryId: Long)

    /** Dissolve every group of [contentType]. */
    suspend fun clearAll(contentType: ContentType)

    /**
     * Set [groupId]'s per-group source ranking: each id in [orderedMemberIds] takes a source priority
     * equal to its position (0 = trunk), and the group's override flag is turned on so aggregation reads
     * this order instead of the global preferred-source list. Atomic.
     */
    suspend fun setSourceOrder(contentType: ContentType, groupId: Long, orderedMemberIds: List<Long>)

    /** Clear [groupId]'s per-group override: reset every member's priority and turn the flag off, so the
     *  group falls back to the global ranking again. Atomic. */
    suspend fun clearSourceOrder(contentType: ContentType, groupId: Long)
}
