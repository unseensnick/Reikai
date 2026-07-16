package mihon.core.migration.migrations

import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupReconstruction
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.NovelRepository
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites

/**
 * One-time migration of the pref-based merge grouping into the persisted merge_group tables (Phase 1 of
 * the merge-system rebuild). Freezes today's groups (manual merges plus same-title auto-groups, honoring
 * deliberate unmerges) as real rows so grouping survives the move off the derive-on-read pref system,
 * with nothing un-grouping.
 *
 * The old prefs are intentionally left intact: they remain the live source of truth until the Phase 2
 * resolution cutover, and old backups still carry them. Best-effort per content type, so a failure on
 * one side does not block startup or the other side.
 */
class MigrateMergePrefsToGroupsMigration : Migration {
    // RK: fires once when the shipped versionCode crosses 184 (the version this rebuild's Phase 1 ships in).
    override val version: Float = 184f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: nothing to migrate
        val prefs = migrationContext.get<ReikaiLibraryPreferences>() ?: return@withIOContext false
        val repo = migrationContext.get<MergeGroupRepository>() ?: return@withIOContext false
        val getFavorites = migrationContext.get<GetFavorites>() ?: return@withIOContext false
        val novelRepo = migrationContext.get<NovelRepository>() ?: return@withIOContext false

        runCatching {
            val groups = MergeGroupReconstruction.reconstruct(
                candidates = getFavorites.await().map {
                    MergeGroupReconstruction.Candidate(it.id, it.title, it.author)
                },
                manualMerges = prefs.mangaManualMerges.get(),
                unmerges = prefs.mangaManualUnmerges.get(),
                autoMergeByTitle = prefs.autoMergeSameTitle.get(),
                requireAuthor = false,
            )
            materialize(repo, ContentType.MANGA, groups)
        }.onFailure { logcat(LogPriority.ERROR, it) { "Merge-group migration failed for manga" } }

        runCatching {
            val groups = MergeGroupReconstruction.reconstruct(
                candidates = novelRepo.getFavorites().map {
                    MergeGroupReconstruction.Candidate(it.id, it.title, it.author)
                },
                manualMerges = prefs.novelManualMerges.get(),
                unmerges = prefs.novelManualUnmerges.get(),
                autoMergeByTitle = prefs.novelAutoMergeSameTitle.get(),
                requireAuthor = prefs.novelAutoMergeRequireAuthor.get(),
            )
            materialize(repo, ContentType.NOVELS, groups)
        }.onFailure { logcat(LogPriority.ERROR, it) { "Merge-group migration failed for novels" } }

        true
    }

    // Idempotent: skip a group whose members are already grouped, so a re-run (or a partial prior run)
    // does not hit the one-group-per-entry constraint.
    private suspend fun materialize(
        repo: MergeGroupRepository,
        contentType: ContentType,
        groups: List<List<Long>>,
    ) {
        for (group in groups) {
            val alreadyGrouped = group.any { repo.getGroupId(contentType, it) != null }
            if (!alreadyGrouped) repo.createGroup(contentType, group)
        }
    }
}
