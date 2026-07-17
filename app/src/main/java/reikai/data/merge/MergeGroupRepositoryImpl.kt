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
            entryIds.distinct().forEach { insertMember(contentType, groupId, it) }
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

    override suspend fun merge(contentType: ContentType, ids: List<Long>): Long? {
        val distinct = ids.distinct()
        if (distinct.size < 2) return null
        return database.transactionWithResult {
            val groupIds = groupIdsForMembers(contentType, distinct)
            // Sorted so a fresh group has a deterministic member order; per-group priority (Phase 3)
            // overrides it later.
            val members = (distinct + groupIds.flatMap { getMembers(contentType, it) }).distinct().sorted()
            if (members.size < 2) return@transactionWithResult null
            groupIds.forEach { queries.deleteGroup(it) }
            queries.insertGroup(contentType.toDbValue())
            val groupId = queries.selectLastInsertedRowId().awaitAsOne()
            members.forEach { insertMember(contentType, groupId, it) }
            groupId
        }
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
            orderedMemberIds.forEachIndexed { index, id -> setMemberPriority(contentType, id, index.toLong()) }
            queries.setOverrideSourceRanking(override = 1L, groupId = groupId)
        }
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

    private suspend fun insertMember(contentType: ContentType, groupId: Long, entryId: Long) {
        when (contentType) {
            ContentType.MANGA -> queries.insertMangaMember(groupId, entryId, DEFAULT_SOURCE_PRIORITY)
            ContentType.NOVELS -> queries.insertNovelMember(groupId, entryId, DEFAULT_SOURCE_PRIORITY)
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
