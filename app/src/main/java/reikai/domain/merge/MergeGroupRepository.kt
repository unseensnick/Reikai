package reikai.domain.merge

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
}
