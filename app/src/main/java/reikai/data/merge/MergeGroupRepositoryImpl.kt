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
        return database.transactionWithResult { absorb(contentType, distinct) }
    }

    override suspend fun materializeGroup(
        contentType: ContentType,
        orderedMemberIds: List<Long>,
        overrideSourceRanking: Boolean,
    ): Long? {
        val distinct = orderedMemberIds.distinct()
        if (distinct.size < 2) return null
        return database.transactionWithResult {
            // Unlike a merge, this states the WHOLE truth about the group it produces, so every prior
            // group of these members goes rather than being folded in. That is what makes undoing a
            // split exact: one writer decides the membership, the order and the flag together,
            // instead of a merge deciding them and a second call correcting the result.
            groupIdsForMembers(contentType, distinct).forEach { queries.deleteGroup(it) }
            queries.insertGroup(contentType.toDbValue())
            val groupId = queries.selectLastInsertedRowId().awaitAsOne()
            distinct.forEachIndexed { index, id -> insertMember(contentType, groupId, id, index.toLong()) }
            if (overrideSourceRanking) queries.setOverrideSourceRanking(override = 1L, groupId = groupId)
            groupId
        }
    }

    /**
     * Fold [ids], and every group they already belong to, into one group; returns it. Caller supplies
     * the transaction.
     *
     * A surviving group row is REUSED, never rebuilt. Every group-owned column then survives by
     * construction (the override flag today, the title and cover overrides that have no writer yet)
     * rather than having to be re-carried by hand wherever a group is formed, which is how rebuilding
     * came to discard a ranking the user set. The survivor is the group of the first id that has one,
     * so "add these sources to that group" keeps that group's identity and its ranking; a group that
     * is absorbed loses its own flag, the same rule [replaceInGroup] has always followed.
     *
     * The survivor's members keep their order and arrivals are APPENDED. Argument order decides the
     * order only for a group that never had one: letting it re-order an existing group put a
     * never-ranked newcomer on the trunk of every hand-ordered group it was added to. A caller that
     * means to state an order outright wants [materializeGroup] instead.
     */
    private suspend fun absorb(contentType: ContentType, ids: List<Long>): Long {
        val survivorId = ids.firstNotNullOfOrNull { getGroupId(contentType, it) }
        val kept = survivorId?.let { getMembers(contentType, it) }.orEmpty()
        val keptSet = kept.toHashSet()
        val order = kept + arrivalOrder(contentType, ids).filterNot { it in keptSet }

        val groupId = survivorId ?: run {
            queries.insertGroup(contentType.toDbValue())
            queries.selectLastInsertedRowId().awaitAsOne()
        }
        absorbedGroups(contentType, ids, into = groupId).forEach { queries.deleteGroup(it) }
        // Priorities are rewritten across the whole group, which also closes the holes a removal
        // leaves behind, so a later default-priority insert cannot land in the middle of the order.
        order.forEachIndexed { index, id ->
            if (id in keptSet) {
                setMemberPriority(contentType, id, index.toLong())
            } else {
                insertMember(contentType, groupId, id, index.toLong())
            }
        }
        return groupId
    }

    /**
     * The order arrivals are appended in: each named id, followed by the members it brings along from
     * its own group in that group's order. A NAMED id keeps its argument position rather than being
     * pulled forward by its group, so a caller naming members in a meaningful order gets it.
     */
    private suspend fun arrivalOrder(contentType: ContentType, ids: List<Long>): List<Long> {
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

    /** The group rows [ids] drag in that are not the survivor: emptied by the absorb, so deleted. */
    private suspend fun absorbedGroups(contentType: ContentType, ids: List<Long>, into: Long): List<Long> =
        groupIdsForMembers(contentType, ids).filterNot { it == into }

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

    override suspend fun replaceInGroup(
        contentType: ContentType,
        oldId: Long,
        newId: Long,
        onDissolve: suspend (memberIds: List<Long>) -> Unit,
    ) {
        database.transaction {
            val groupId = getGroupId(contentType, oldId) ?: return@transaction
            val before = getMembers(contentType, groupId)
            deleteMember(contentType, oldId)
            when (val newGroupId = getGroupId(contentType, newId)) {
                groupId -> {}
                null -> insertMember(contentType, groupId, newId)
                // The new member brings its own group along, exactly as an absorb does: this group's
                // row survives and keeps its own ranking, the absorbed one's row goes. Its members
                // stay merged, so this is not a dissolve and the hook does not fire.
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
            // dissolved here, matching removeFromGroup. This one IS a break-up, so the hook runs
            // with the group as it stood, while its members are still favorited.
            val remaining = getMembers(contentType, groupId)
            if (remaining.size < 2) {
                onDissolve(before)
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
