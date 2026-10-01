package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.dedupe.MergedDuplicateRepository
import reikai.domain.dedupe.survivorIds
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupReconstruction
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.NovelRepository
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites

/**
 * One-time migration of the pref-based merge grouping into the persisted merge_group tables (part of
 * the merge-system rebuild). Freezes today's groups (manual merges plus same-title auto-groups, honoring
 * deliberate unmerges) as real rows so grouping survives the move off the derive-on-read pref system,
 * with nothing un-grouping.
 *
 * The old prefs are frozen input: this is their only reader, for an install upgrading past 189. Restore
 * skips them and rebuilds a 0.3.x backup's groups from the backup itself, through the same kernel.
 * Best-effort per content type, so a failure on one side does not block startup or the other side.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class MigrateMergePrefsToGroupsMigration(
    private val prefs: ReikaiLibraryPreferences,
    private val repo: MergeGroupRepository,
    private val getFavorites: GetFavorites,
    private val novelRepo: NovelRepository,
    private val mergedDuplicates: MergedDuplicateRepository,
) : Migration {
    // Fires once when the shipped versionCode crosses 189. Must stay above every shipped release's
    // versionCode (0.3.1 is 184): a migration runs only for old < version <= new, so a gate at or below
    // an installed build's code never fires there, and that install keeps no groups at all.
    override val version: Float = 189f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: nothing to migrate

        runCatching {
            val candidates = getFavorites.await().map { MergeGroupReconstruction.Candidate(it.id, it.title, it.author) }
            val groups = reconstruct(ContentType.MANGA, candidates, prefs.mangaManualMerges, prefs.mangaManualUnmerges)
            materialize(repo, ContentType.MANGA, groups)
        }.onFailure { logcat(LogPriority.ERROR, it) { "Merge-group migration failed for manga" } }

        runCatching {
            val candidates = novelRepo.getFavorites().map {
                MergeGroupReconstruction.Candidate(it.id, it.title, it.author)
            }
            val groups = reconstruct(ContentType.NOVELS, candidates, prefs.novelManualMerges, prefs.novelManualUnmerges)
            materialize(repo, ContentType.NOVELS, groups)
        }.onFailure { logcat(LogPriority.ERROR, it) { "Merge-group migration failed for novels" } }

        true
    }

    private suspend fun reconstruct(
        contentType: ContentType,
        candidates: List<MergeGroupReconstruction.Candidate>,
        merges: Preference<Set<String>>,
        unmerges: Preference<Set<String>>,
    ): List<List<Long>> {
        // The upgrade's dedupe (50.sqm, 51.sqm) runs before this, so the prefs can name a copy it merged away.
        // The record is emptied only after the migrations, by MergedDuplicateCarryMigration.
        val survivors = mergedDuplicates.getAll().survivorIds(contentType)
        return MergeGroupReconstruction.reconstruct(
            candidates = candidates,
            manualMerges = MergeGroupReconstruction.parsePrefGroups(merges.get(), survivors),
            unmerges = MergeGroupReconstruction.parsePrefGroups(unmerges.get(), survivors),
            switches = MergeGroupReconstruction.titleSwitches(contentType, prefs) { it.get() },
        )
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
