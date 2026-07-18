package reikai.domain.manga

import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import tachiyomi.domain.manga.model.Manga

/**
 * Manga source-grouping operations for the details / library screens, backed by the persisted merge
 * group tables ([MergeGroupRepository]). Holds no per-screen state: callers keep their own group ids and
 * pass them in. Kept as the caller-facing API so the screens don't depend on the repository directly.
 *
 * The [ReikaiLibraryPreferences.seriesMergingEnabled] master switch gates resolution: when off, every
 * series resolves standalone (groups are preserved, just not shown), so flipping it back on restores them.
 */
class MangaMergeManager(
    private val repository: MergeGroupRepository,
    private val preferences: ReikaiLibraryPreferences,
) {

    /** The group [targetId] belongs to, or just itself when it is ungrouped or merging is disabled. */
    suspend fun computeRelatedMangaIds(targetId: Long): LongArray {
        if (!preferences.seriesMergingEnabled.get()) return longArrayOf(targetId)
        val groupId = repository.getGroupId(ContentType.MANGA, targetId) ?: return longArrayOf(targetId)
        return repository.getMembers(ContentType.MANGA, groupId).toLongArray()
    }

    /**
     * Split [targetIds] out of their group, keeping the survivors grouped; the group is dissolved when
     * fewer than two members remain (the "remove all sources" case). Returns the surviving ids.
     */
    suspend fun removeFromGroup(relatedMangaIds: LongArray, targetIds: List<Long>): LongArray {
        if (targetIds.isEmpty()) return relatedMangaIds
        return repository.removeFromGroup(ContentType.MANGA, targetIds).toLongArray()
    }

    /** Manage-sources split. Same as [removeFromGroup] now that the repository auto-dissolves a group
     *  left with fewer than two members; kept as a distinct entry point for the manage-sources dialog. */
    suspend fun splitOrDissolve(relatedMangaIds: LongArray, targetIds: List<Long>): LongArray =
        removeFromGroup(relatedMangaIds, targetIds)

    /** Merge [ids] into one group, absorbing any groups they already belong to. No-op for < 2 entries. */
    suspend fun mergeManga(ids: List<Long>) {
        repository.merge(ContentType.MANGA, ids)
    }

    /**
     * Whether the add-time duplicate dialog offers grouping (the picker and the "add to existing group"
     * action). Needs the same-title suggestion pref and the master switch: with grouping switched off
     * every series resolves standalone, so offering to group would build a group nothing displays.
     */
    val suggestGroupingOnAdd: Boolean
        get() = preferences.seriesMergingEnabled.get() && preferences.autoMergeSameTitle.get()

    /**
     * Group id per id in [mangaIds] for callers rendering group-collapsed cards (the add-time duplicate
     * dialog). Ungrouped ids are absent. One batch query rather than a read per id. Empty when merging is
     * disabled, so nothing collapses while groups are hidden, matching every other resolution path.
     */
    suspend fun groupIdsFor(mangaIds: List<Long>): Map<Long, Long> {
        if (!preferences.seriesMergingEnabled.get()) return emptyMap()
        val memberships = repository.getAllMemberships(ContentType.MANGA)
        return mangaIds.mapNotNull { id -> memberships[id]?.let { id to it } }.toMap()
    }

    /**
     * The group of [anchorId] in its own priority order when the group overrides the global source
     * ranking, else empty (aggregation then falls back to the global list). This is the per-group
     * override channel: aggregation ranks members by this order directly, so two members sharing a
     * source still order distinctly (a source-id list could not tell them apart).
     */
    suspend fun overrideRankingMemberIds(anchorId: Long): List<Long> {
        val groupId = repository.getGroupId(ContentType.MANGA, anchorId) ?: return emptyList()
        if (repository.getGroup(groupId)?.overrideSourceRanking != true) return emptyList()
        return repository.getMembers(ContentType.MANGA, groupId)
    }

    /** Persist [orderedMemberIds] as the group's source order (0 = trunk) and turn the override on. The
     *  ids are the manage-sources rows after a drag; the group is resolved from the first of them. */
    suspend fun setSourceOrder(orderedMemberIds: List<Long>) {
        val groupId = orderedMemberIds.firstNotNullOfOrNull { repository.getGroupId(ContentType.MANGA, it) } ?: return
        repository.setSourceOrder(ContentType.MANGA, groupId, orderedMemberIds)
    }

    /** Clear the per-group override for [anchorId]'s group (back to the global ranking). */
    suspend fun clearSourceOrder(anchorId: Long) {
        val groupId = repository.getGroupId(ContentType.MANGA, anchorId) ?: return
        repository.clearSourceOrder(ContentType.MANGA, groupId)
    }

    /** Merge the library selection into one group. Each selected id's whole group is absorbed by
     *  [MergeGroupRepository.merge], so passing the collapsed cards' representative ids is enough. */
    suspend fun mergeSelectedManga(ids: List<Long>) {
        repository.merge(ContentType.MANGA, ids)
    }

    /** Fully dissolve the group of each of [targetIds] (the library bulk "Unmerge"). Ungrouped targets
     *  are skipped. */
    suspend fun unmergeManga(targetIds: List<Long>) {
        targetIds.distinct().forEach { repository.dissolve(ContentType.MANGA, it) }
    }

    /**
     * Group key per favorite for the Updates group-by-series feature: members of one group share a key so
     * they collapse together, and every ungrouped series gets its own key. Favorites are passed in so the
     * caller controls the DB read; the memberships come from one batch query.
     */
    suspend fun seriesGroupKeys(favorites: List<Manga>): Map<Long, String> {
        if (!preferences.seriesMergingEnabled.get()) return favorites.associate { it.id to "m${it.id}" }
        val memberships = repository.getAllMemberships(ContentType.MANGA)
        return favorites.associate { manga -> manga.id to (memberships[manga.id]?.let { "g$it" } ?: "m${manga.id}") }
    }

    /** Dissolve every manga group (the Settings "Clear all merges" action). */
    suspend fun clearAllMergesIncludingAuto() {
        repository.clearAll(ContentType.MANGA)
    }
}
