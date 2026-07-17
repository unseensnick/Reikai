package reikai.domain.merge

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ContentType
import reikai.domain.merge.model.MergeGroup

/**
 * Storage for persisted merge groups (Phase 0 of the merge-system rebuild). One repository for both
 * content types, parameterized by [ContentType]; the manga and novel members live in separate tables
 * (they FK-cascade to their own entry table), but the group identity and this API are shared.
 *
 * Nothing in the app resolves groups through this yet: the pref-based path still runs. The resolution
 * cutover (Phase 2) moves callers here. [ContentType.ALL] is not a valid group type.
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
     * Merge [ids] into one group, absorbing any groups they already belong to (so merging two collapsed
     * cards pulls in every hidden member). Atomic. Returns the resulting group id, or null when fewer
     * than two distinct entries would take part.
     */
    suspend fun merge(contentType: ContentType, ids: List<Long>): Long?

    /**
     * Remove [targetIds] from their group and return the surviving members. If the removal leaves fewer
     * than two members, the group is dissolved (the lone survivor becomes standalone). Atomic.
     */
    suspend fun removeFromGroup(contentType: ContentType, targetIds: List<Long>): List<Long>

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
