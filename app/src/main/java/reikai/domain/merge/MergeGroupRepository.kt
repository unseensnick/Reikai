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

    /** Member entry ids of [groupId], ordered by source priority then insertion order. */
    suspend fun getMembers(contentType: ContentType, groupId: Long): List<Long>

    /**
     * Create a group over [entryIds] and return its id. Returns null for fewer than two distinct ids
     * (a group of one is meaningless). New members start at the default source priority, so a fresh
     * group falls back to the global preferred-source ranking until it explicitly overrides.
     */
    suspend fun createGroup(contentType: ContentType, entryIds: List<Long>): Long?

    /** Add [entryIds] to an existing [groupId]. */
    suspend fun addMembers(contentType: ContentType, groupId: Long, entryIds: List<Long>)

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
     * Merge [ids] into one group, absorbing any groups they already belong to (so merging two collapsed
     * cards pulls in every hidden member). Atomic. Returns the resulting group id, or null when fewer
     * than two distinct entries would take part.
     *
     * The group of the first id that has one SURVIVES, keeping its id, its member order and its
     * per-group ranking; arrivals are appended after its members. So adding a source to a group the
     * user ordered by hand leaves that order alone and puts the newcomer last, and an absorbed group's
     * own ranking does not follow it across. A caller stating a whole group outright, order and flag
     * together, wants [materializeGroup] instead.
     */
    suspend fun merge(contentType: ContentType, ids: List<Long>): Long?

    /**
     * Replace whatever grouping [orderedMemberIds] currently have with exactly one group: these members,
     * in this order, with this [overrideSourceRanking]. Atomic. Returns the group id, or null for fewer
     * than two distinct ids.
     *
     * The counterpart to [merge] for a caller that knows the whole answer: undoing a source split, and
     * materializing a backed-up group. Distinct from merge because a merge folds in members it was not
     * given and cannot restore a flag whose group row is already gone, so restoring through it took a
     * merge plus a correction, leaving two writers deciding the same two facts.
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
     * Atomically swap [oldId] out of its group and [newId] in (a replace migration). One transaction,
     * because remove-then-merge as two calls left a window where a cancellation stranded the group
     * half-swapped and a retry, seeing [oldId] already ungrouped, silently skipped the join. No-op
     * when [oldId] is ungrouped, which is also what makes a retry after full success harmless. As
     * with [removeFromGroup], a group left with fewer than two members is dissolved.
     *
     * [onDissolve] runs, inside the transaction, with the group's members as they stood, for the one
     * case that really breaks a group up: migrating onto a sibling, which leaves the target alone.
     * Absorbing the arriving member's group is not a break-up (those members stay merged) and does
     * not call it. The hook exists because a dissolving group has to hand each member its own copy of
     * the shared tracker binding first; see [EntryMergeManager].
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
