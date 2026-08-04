package reikai.data.merge

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.model.MergeGroup
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

class MergeGroupRepositoryImpl(
    private val database: Database,
) : MergeGroupRepository {

    private val queries = database.merge_groupQueries

    override suspend fun getGroup(groupId: Long): MergeGroup? =
        queries.getGroup(groupId, ::mapGroup).awaitAsOneOrNull()

    override suspend fun getGroupId(contentType: ContentType, entryId: Long): Long? =
        when (contentType) {
            ContentType.MANGA -> queries.mangaGroupId(entryId).awaitAsOneOrNull()
            ContentType.NOVELS -> queries.novelGroupId(entryId).awaitAsOneOrNull()
            ContentType.ALL -> error(ALL_UNSUPPORTED)
        }

    override suspend fun getMembers(contentType: ContentType, groupId: Long): List<Long> =
        when (contentType) {
            ContentType.MANGA -> queries.mangaMembers(groupId).awaitAsList()
            ContentType.NOVELS -> queries.novelMembers(groupId).awaitAsList()
            ContentType.ALL -> error(ALL_UNSUPPORTED)
        }

    override suspend fun createGroup(contentType: ContentType, entryIds: List<Long>): Long? {
        val distinct = entryIds.distinct()
        if (distinct.size < 2) return null
        return database.transactionWithResult {
            queries.insertGroup(contentType.toDbValue())
            val groupId = queries.selectLastInsertedRowId().awaitAsOne()
            distinct.forEach { insertMember(contentType, groupId, it) }
            groupId
        }
    }

    override suspend fun addMembers(contentType: ContentType, groupId: Long, entryIds: List<Long>) {
        database.transaction {
            val existing = getMembers(contentType, groupId)
            val added = entryIds.distinct().filterNot { it in existing }
            added.forEach { insertMember(contentType, groupId, it) }
            // Appended, never interleaved: a group can carry explicit priorities, and a fresh row at
            // the default would sort into the middle of one instead of onto the end.
            writeOrder(contentType, existing + added)
        }
    }

    override suspend fun removeMembers(contentType: ContentType, entryIds: List<Long>) {
        database.transaction {
            entryIds.forEach { id ->
                when (contentType) {
                    ContentType.MANGA -> queries.deleteMangaMember(id)
                    ContentType.NOVELS -> queries.deleteNovelMember(id)
                    ContentType.ALL -> error(ALL_UNSUPPORTED)
                }
            }
        }
    }

    override suspend fun dissolveGroup(groupId: Long) {
        queries.deleteGroup(groupId)
    }

    override suspend fun getAllMemberships(contentType: ContentType): Map<Long, Long> =
        when (contentType) {
            ContentType.MANGA -> queries.allMangaMemberships { id, groupId -> id to groupId }.awaitAsList().toMap()
            ContentType.NOVELS -> queries.allNovelMemberships { id, groupId -> id to groupId }.awaitAsList().toMap()
            ContentType.ALL -> error(ALL_UNSUPPORTED)
        }

    override fun getAllMembershipsAsFlow(contentType: ContentType): Flow<Map<Long, Long>> =
        when (contentType) {
            ContentType.MANGA -> queries.allMangaMemberships { id, groupId -> id to groupId }
                .subscribeToList().map { it.toMap() }
            ContentType.NOVELS -> queries.allNovelMemberships { id, groupId -> id to groupId }
                .subscribeToList().map { it.toMap() }
            ContentType.ALL -> error(ALL_UNSUPPORTED)
        }

    override fun getOverrideRankingsAsFlow(contentType: ContentType): Flow<Map<Long, List<Long>>> =
        when (contentType) {
            // Rows arrive already in (group, source_priority) order, so groupBy preserves the trunk order.
            ContentType.MANGA -> queries.mangaOverrideRankings { groupId, mangaId -> groupId to mangaId }
                .subscribeToList().map { rows -> rows.groupBy({ it.first }, { it.second }) }
            ContentType.NOVELS -> queries.novelOverrideRankings { groupId, novelId -> groupId to novelId }
                .subscribeToList().map { rows -> rows.groupBy({ it.first }, { it.second }) }
            ContentType.ALL -> error(ALL_UNSUPPORTED)
        }

    override suspend fun merge(contentType: ContentType, ids: List<Long>): Long? {
        val distinct = ids.distinct()
        if (distinct.size < 2) return null
        return database.transactionWithResult {
            val groupIds = groupIdsForMembers(contentType, distinct)
            val members = mergedOrder(contentType, distinct)
            // The override survives if ANY absorbed group carried one. Rebuilding the group without
            // it silently discarded a ranking the user set by hand, on every path that merges:
            // undoing a source split, adding a source to a group, and backup restore.
            val override = groupIds.any { getGroup(it)?.overrideSourceRanking == true }
            groupIds.forEach { queries.deleteGroup(it) }
            queries.insertGroup(contentType.toDbValue())
            val groupId = queries.selectLastInsertedRowId().awaitAsOne()
            members.forEachIndexed { index, id -> insertMember(contentType, groupId, id, index.toLong()) }
            if (override) queries.setOverrideSourceRanking(override = 1L, groupId = groupId)
            groupId
        }
    }

    /**
     * The member order a merge produces.
     *
     * Argument order decides it, because argument order is meaningful at every call site: undoing a
     * split passes the prior group in its own trunk order, and adding to a group passes the anchor
     * first. A member that is absorbed without being named (the rest of a group one of the ids
     * belongs to) follows the id that brought it in, in its own group's order.
     *
     * A NAMED member keeps its argument position rather than being pulled forward by its group. That
     * is what makes undoing a split an exact restoration: the split member is named in the middle of
     * the prior order, and expanding its old group first would push it to the end instead.
     *
     * The old rule sorted by id, so the trunk of every merged group was an accident of insertion
     * order and an undo was a reshuffle.
     */
    private suspend fun mergedOrder(contentType: ContentType, ids: List<Long>): List<Long> {
        val named = ids.toHashSet()
        val ordered = LinkedHashSet<Long>()
        ids.forEach { id ->
            if (!ordered.add(id)) return@forEach
            val groupId = getGroupId(contentType, id) ?: return@forEach
            getMembers(contentType, groupId).forEach { member ->
                if (member !in named) ordered.add(member)
            }
        }
        return ordered.toList()
    }

    override suspend fun removeFromGroup(contentType: ContentType, targetIds: List<Long>): List<Long> {
        if (targetIds.isEmpty()) return emptyList()
        return database.transactionWithResult {
            val groupId = targetIds.firstNotNullOfOrNull { getGroupId(contentType, it) }
                ?: return@transactionWithResult emptyList()
            val targetSet = targetIds.toHashSet()
            val survivors = getMembers(contentType, groupId).filter { it !in targetSet }
            targetIds.forEach { deleteMember(contentType, it) }
            if (survivors.size < 2) queries.deleteGroup(groupId)
            survivors
        }
    }

    override suspend fun replaceInGroup(contentType: ContentType, oldId: Long, newId: Long) {
        database.transaction {
            val groupId = getGroupId(contentType, oldId) ?: return@transaction
            val before = getMembers(contentType, groupId)
            deleteMember(contentType, oldId)
            when (val newGroupId = getGroupId(contentType, newId)) {
                groupId -> {}
                null -> insertMember(contentType, groupId, newId)
                // The new member brings its own group along, like merge() would.
                else -> {
                    getMembers(contentType, newGroupId).forEach { member ->
                        deleteMember(contentType, member)
                        insertMember(contentType, groupId, member)
                    }
                    queries.deleteGroup(newGroupId)
                }
            }
            // A group of one is not a group. Migrating onto a sibling of the same group leaves the
            // target alone in it, and an empty group is left by a replace inside a pair; both are
            // dissolved here, matching removeFromGroup.
            val remaining = getMembers(contentType, groupId)
            if (remaining.size < 2) {
                queries.deleteGroup(groupId)
                return@transaction
            }
            // The arriving member takes the outgoing one's slot: a migration swaps one source for
            // another, it does not re-rank the group. A fresh row otherwise sorts last (default
            // priority, higher rowid), so migrating the trunk quietly demoted it to the bottom.
            val beforeSet = before.toHashSet()
            val arrived = remaining.filterNot { it in beforeSet }
            val remainingSet = remaining.toHashSet()
            writeOrder(
                contentType,
                before.flatMap { member ->
                    when {
                        member == oldId -> arrived
                        member in remainingSet -> listOf(member)
                        else -> emptyList()
                    }
                },
            )
        }
    }

    override suspend fun dissolve(contentType: ContentType, entryId: Long) {
        database.transaction {
            val groupId = getGroupId(contentType, entryId) ?: return@transaction
            queries.deleteGroup(groupId)
        }
    }

    override suspend fun clearAll(contentType: ContentType) {
        queries.deleteGroupsByContentType(contentType.toDbValue())
    }

    override suspend fun setSourceOrder(contentType: ContentType, groupId: Long, orderedMemberIds: List<Long>) {
        database.transaction {
            writeOrder(contentType, orderedMemberIds)
            queries.setOverrideSourceRanking(override = 1L, groupId = groupId)
        }
    }

    /** Persist [orderedMemberIds] as source_priority 0..n-1, making the order explicit instead of
     *  leaving it to the rowid tiebreak that any later insert would land in the middle of. */
    private suspend fun writeOrder(contentType: ContentType, orderedMemberIds: List<Long>) {
        orderedMemberIds.forEachIndexed { index, id -> setMemberPriority(contentType, id, index.toLong()) }
    }

    override suspend fun clearSourceOrder(contentType: ContentType, groupId: Long) {
        database.transaction {
            getMembers(contentType, groupId).forEach { setMemberPriority(contentType, it, DEFAULT_SOURCE_PRIORITY) }
            queries.setOverrideSourceRanking(override = 0L, groupId = groupId)
        }
    }

    private suspend fun setMemberPriority(contentType: ContentType, entryId: Long, priority: Long) {
        when (contentType) {
            ContentType.MANGA -> queries.setMangaMemberPriority(priority, entryId)
            ContentType.NOVELS -> queries.setNovelMemberPriority(priority, entryId)
            ContentType.ALL -> error(ALL_UNSUPPORTED)
        }
    }

    private suspend fun groupIdsForMembers(contentType: ContentType, ids: List<Long>): List<Long> =
        when (contentType) {
            ContentType.MANGA -> queries.mangaGroupIdsForMembers(ids).awaitAsList()
            ContentType.NOVELS -> queries.novelGroupIdsForMembers(ids).awaitAsList()
            ContentType.ALL -> error(ALL_UNSUPPORTED)
        }

    private suspend fun deleteMember(contentType: ContentType, entryId: Long) {
        when (contentType) {
            ContentType.MANGA -> queries.deleteMangaMember(entryId)
            ContentType.NOVELS -> queries.deleteNovelMember(entryId)
            ContentType.ALL -> error(ALL_UNSUPPORTED)
        }
    }

    private suspend fun insertMember(
        contentType: ContentType,
        groupId: Long,
        entryId: Long,
        priority: Long = DEFAULT_SOURCE_PRIORITY,
    ) {
        when (contentType) {
            ContentType.MANGA -> queries.insertMangaMember(groupId, entryId, priority)
            ContentType.NOVELS -> queries.insertNovelMember(groupId, entryId, priority)
            ContentType.ALL -> error(ALL_UNSUPPORTED)
        }
    }

    private fun mapGroup(
        id: Long,
        contentType: Long,
        titleOverride: String?,
        coverOverride: String?,
        overrideSourceRanking: Long,
    ) = MergeGroup(
        id = id,
        contentType = contentType.toContentType(),
        titleOverride = titleOverride,
        coverOverride = coverOverride,
        overrideSourceRanking = overrideSourceRanking != 0L,
    )

    companion object {
        private const val DEFAULT_SOURCE_PRIORITY = 0L

        // Stable persisted values, mapped by enum constant (not ordinal, which ContentType.ALL would shift).
        private const val DB_MANGA = 0L
        private const val DB_NOVEL = 1L
        private const val ALL_UNSUPPORTED = "ContentType.ALL is not a valid merge-group type"

        private fun ContentType.toDbValue(): Long = when (this) {
            ContentType.MANGA -> DB_MANGA
            ContentType.NOVELS -> DB_NOVEL
            ContentType.ALL -> error(ALL_UNSUPPORTED)
        }

        private fun Long.toContentType(): ContentType = when (this) {
            DB_MANGA -> ContentType.MANGA
            DB_NOVEL -> ContentType.NOVELS
            else -> error("Unknown merge-group content_type $this")
        }
    }
}
