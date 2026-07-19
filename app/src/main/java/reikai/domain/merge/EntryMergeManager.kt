package reikai.domain.merge

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences

/**
 * Source-grouping operations for the details / library / updates screens of one content type, backed by
 * the persisted merge group tables ([MergeGroupRepository]). Holds no per-screen state: callers keep their
 * own group ids and pass them in. One class serves both manga and novels; the content type is fixed at
 * construction by the thin [reikai.domain.manga.MangaMergeManager] / [reikai.domain.novel.NovelMergeManager]
 * subclasses, so injectors still resolve a manager by content type while the logic lives here once.
 *
 * The [ReikaiLibraryPreferences.seriesMergingEnabled] master switch gates resolution: when off, every
 * entry resolves standalone (groups are preserved, just not shown), so flipping it back on restores them.
 */
open class EntryMergeManager(
    private val contentType: ContentType,
    private val repository: MergeGroupRepository,
    private val preferences: ReikaiLibraryPreferences,
) : MergeManager {

    private val isNovel: Boolean get() = contentType == ContentType.NOVELS

    /** The group [targetId] belongs to, or just itself when it is ungrouped or merging is disabled. */
    suspend fun computeRelatedIds(targetId: Long): LongArray {
        if (!preferences.seriesMergingEnabled.get()) return longArrayOf(targetId)
        val groupId = repository.getGroupId(contentType, targetId) ?: return longArrayOf(targetId)
        return repository.getMembers(contentType, groupId).toLongArray()
    }

    /** [computeRelatedIds] as a `List` for callers (the novel reader / tracking path) that want one. */
    suspend fun relatedIdsList(targetId: Long): List<Long> = computeRelatedIds(targetId).toList()

    override suspend fun merge(ids: List<Long>) {
        repository.merge(contentType, ids)
    }

    override fun membershipChanges(): Flow<Map<Long, Long>> =
        repository.getAllMembershipsAsFlow(contentType)

    /**
     * Split [targetIds] out of their group, keeping the survivors grouped; the group is dissolved when
     * fewer than two members remain (the "remove all sources" case). Returns the surviving ids.
     */
    override suspend fun removeFromGroup(relatedIds: LongArray, targetIds: List<Long>): LongArray {
        if (targetIds.isEmpty()) return relatedIds
        return repository.removeFromGroup(contentType, targetIds).toLongArray()
    }

    /**
     * Whether the add-time duplicate dialog offers grouping (the picker and the "add to existing group"
     * action). Needs the same-title suggestion pref and the master switch: with grouping switched off
     * every series resolves standalone, so offering to group would build a group nothing displays.
     */
    val suggestGroupingOnAdd: Boolean
        get() = preferences.seriesMergingEnabled.get() && sameTitlePreference.get()

    private val sameTitlePreference
        get() = if (isNovel) preferences.novelAutoMergeSameTitle else preferences.autoMergeSameTitle

    /**
     * Group id per id in [ids] for callers rendering group-collapsed cards (the add-time duplicate
     * dialog). Ungrouped ids are absent. One batch query rather than a read per id. Empty when merging is
     * disabled, so nothing collapses while groups are hidden, matching every other resolution path.
     */
    suspend fun groupIdsFor(ids: List<Long>): Map<Long, Long> {
        if (!preferences.seriesMergingEnabled.get()) return emptyMap()
        val memberships = repository.getAllMemberships(contentType)
        return ids.mapNotNull { id -> memberships[id]?.let { id to it } }.toMap()
    }

    /**
     * The group of [anchorId] in its own priority order when the group overrides the global source
     * ranking, else empty (aggregation then falls back to the global list). This is the per-group
     * override channel: aggregation ranks members by this order directly, so two members sharing a
     * source still order distinctly (a source-id list could not tell them apart).
     */
    suspend fun overrideRankingMemberIds(anchorId: Long): List<Long> {
        val groupId = repository.getGroupId(contentType, anchorId) ?: return emptyList()
        if (repository.getGroup(groupId)?.overrideSourceRanking != true) return emptyList()
        return repository.getMembers(contentType, groupId)
    }

    /** Persist [orderedMemberIds] as the group's source order (0 = trunk) and turn the override on. The
     *  ids are the manage-sources rows after a drag; the group is resolved from the first of them. */
    override suspend fun setSourceOrder(orderedMemberIds: List<Long>) {
        val groupId = orderedMemberIds.firstNotNullOfOrNull { repository.getGroupId(contentType, it) } ?: return
        repository.setSourceOrder(contentType, groupId, orderedMemberIds)
    }

    /** Clear the per-group override for [anchorId]'s group (back to the global ranking). */
    override suspend fun clearSourceOrder(anchorId: Long) {
        val groupId = repository.getGroupId(contentType, anchorId) ?: return
        repository.clearSourceOrder(contentType, groupId)
    }

    /** Fully dissolve the group of each of [targetIds] (the library bulk "Unmerge"). Ungrouped targets
     *  are skipped. */
    suspend fun unmerge(targetIds: List<Long>) {
        targetIds.distinct().forEach { repository.dissolve(contentType, it) }
    }

    /**
     * Group key per favorite id for the Updates group-by-series feature: members of one group share a key
     * so they collapse together, and every ungrouped series gets its own key. Favorite ids are passed in so
     * the caller controls the DB read; the memberships come from one batch query.
     */
    suspend fun seriesGroupKeys(favoriteIds: List<Long>): Map<Long, String> {
        val standalonePrefix = if (isNovel) "n" else "m"
        if (!preferences.seriesMergingEnabled.get()) {
            return favoriteIds.associateWith { "$standalonePrefix$it" }
        }
        val memberships = repository.getAllMemberships(contentType)
        return favoriteIds.associateWith { id -> memberships[id]?.let { "g$it" } ?: "$standalonePrefix$id" }
    }

    /** Dissolve every group of this content type (the Settings "Clear all merges" action). */
    suspend fun clearAllMergesIncludingAuto() {
        repository.clearAll(contentType)
    }
}
