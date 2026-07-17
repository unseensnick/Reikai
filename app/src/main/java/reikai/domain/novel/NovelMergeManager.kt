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
