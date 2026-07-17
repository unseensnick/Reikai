package reikai.domain.novel

import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.model.Novel

/**
 * Novel source-grouping operations, the novel analogue of [reikai.domain.manga.MangaMergeManager], backed
 * by the persisted merge group tables ([MergeGroupRepository]). Holds no per-screen state.
 *
 * The [ReikaiLibraryPreferences.seriesMergingEnabled] master switch gates resolution: when off, every
 * novel resolves standalone (groups are preserved, just not shown). Author matching is gone: membership
 * is an explicit stored fact, so there is no title/author derivation to guard.
 */
class NovelMergeManager(
    private val repository: MergeGroupRepository,
    private val preferences: ReikaiLibraryPreferences,
) {

    /** The group [targetId] belongs to, or just itself when ungrouped or merging is disabled. [title] /
     *  [author] are kept for the call-site signature; membership no longer derives from them. */
    suspend fun computeRelatedNovelIds(targetId: Long, title: String, author: String?): LongArray {
        if (!preferences.seriesMergingEnabled.get()) return longArrayOf(targetId)
        val groupId = repository.getGroupId(ContentType.NOVELS, targetId) ?: return longArrayOf(targetId)
        return repository.getMembers(ContentType.NOVELS, groupId).toLongArray()
    }

    /** Group ids for a novel by id alone (the entry point group-aware tracking uses). */
    suspend fun relatedNovelIdsFor(novelId: Long): List<Long> {
        if (!preferences.seriesMergingEnabled.get()) return listOf(novelId)
        val groupId = repository.getGroupId(ContentType.NOVELS, novelId) ?: return listOf(novelId)
        return repository.getMembers(ContentType.NOVELS, groupId)
    }

    /** Merge [ids] into one group, absorbing any groups they already belong to. No-op for < 2 entries. */
    suspend fun mergeNovels(ids: List<Long>) {
        repository.merge(ContentType.NOVELS, ids)
    }

    /**
     * Whether the add-time duplicate dialog offers grouping (the picker and the "add to existing group"
     * action). Needs the novel same-title suggestion pref and the shared master switch: with grouping
     * switched off every series resolves standalone, so offering to group would build a hidden group.
     */
    val suggestGroupingOnAdd: Boolean
        get() = preferences.seriesMergingEnabled.get() && preferences.novelAutoMergeSameTitle.get()

    /**
     * Group id per id in [novelIds] for callers rendering group-collapsed cards (the add-time duplicate
     * dialog). Ungrouped ids are absent. One batch query rather than a read per id. Empty when merging is
     * disabled, so nothing collapses while groups are hidden, matching every other resolution path.
     */
    suspend fun groupIdsFor(novelIds: List<Long>): Map<Long, Long> {
        if (!preferences.seriesMergingEnabled.get()) return emptyMap()
        val memberships = repository.getAllMemberships(ContentType.NOVELS)
        return novelIds.mapNotNull { id -> memberships[id]?.let { id to it } }.toMap()
    }

    /**
     * The group of [anchorId] in its own priority order when the group overrides the global source
     * ranking, else empty (aggregation then falls back to the global list). This is the per-group
     * override channel: aggregation ranks members by this order directly, so two members sharing a
     * source still order distinctly (a source-id list could not tell them apart).
     */
    suspend fun overrideRankingMemberIds(anchorId: Long): List<Long> {
        val groupId = repository.getGroupId(ContentType.NOVELS, anchorId) ?: return emptyList()
        if (repository.getGroup(groupId)?.overrideSourceRanking != true) return emptyList()
        return repository.getMembers(ContentType.NOVELS, groupId)
    }

    /** Persist [orderedMemberIds] as the group's source order (0 = trunk) and turn the override on. The
     *  ids are the manage-sources rows after a drag; the group is resolved from the first of them. */
    suspend fun setSourceOrder(orderedMemberIds: List<Long>) {
        val groupId = orderedMemberIds.firstNotNullOfOrNull { repository.getGroupId(ContentType.NOVELS, it) } ?: return
        repository.setSourceOrder(ContentType.NOVELS, groupId, orderedMemberIds)
    }

    /** Clear the per-group override for [anchorId]'s group (back to the global ranking). */
    suspend fun clearSourceOrder(anchorId: Long) {
        val groupId = repository.getGroupId(ContentType.NOVELS, anchorId) ?: return
        repository.clearSourceOrder(ContentType.NOVELS, groupId)
    }

    /** Merge the library selection into one group; each selected id's whole group is absorbed. */
    suspend fun mergeSelectedNovels(ids: List<Long>) {
        repository.merge(ContentType.NOVELS, ids)
    }

    /** Fully dissolve the group of each of [targetIds] (the library bulk "Unmerge"). */
    suspend fun unmergeNovels(targetIds: List<Long>) {
        targetIds.distinct().forEach { repository.dissolve(ContentType.NOVELS, it) }
    }

    /**
     * Split [targetIds] out of their group, keeping the survivors grouped; the group is dissolved when
     * fewer than two members remain. Returns the surviving ids.
     */
    suspend fun removeFromGroup(relatedNovelIds: LongArray, targetIds: List<Long>): LongArray {
        if (targetIds.isEmpty()) return relatedNovelIds
        return repository.removeFromGroup(ContentType.NOVELS, targetIds).toLongArray()
    }

    /** Manage-sources split. Same as [removeFromGroup] now that the repository auto-dissolves. */
    suspend fun splitOrDissolve(relatedNovelIds: LongArray, targetIds: List<Long>): LongArray =
        removeFromGroup(relatedNovelIds, targetIds)

    /** Dissolve every novel group. Both Settings "clear" actions map here. */
    suspend fun clearManualMerges() {
        repository.clearAll(ContentType.NOVELS)
    }

    /** Dissolve every novel group (see [clearManualMerges]). */
    suspend fun clearAllMergesIncludingAuto() {
        repository.clearAll(ContentType.NOVELS)
    }

    /** Group key per favorite for the Updates group-by-series feature: group members share a key, every
     *  ungrouped novel gets its own. */
    suspend fun seriesGroupKeys(favorites: List<Novel>): Map<Long, String> {
        if (!preferences.seriesMergingEnabled.get()) return favorites.associate { it.id to "n${it.id}" }
        val memberships = repository.getAllMemberships(ContentType.NOVELS)
        return favorites.associate { novel -> novel.id to (memberships[novel.id]?.let { "g$it" } ?: "n${novel.id}") }
    }
}
