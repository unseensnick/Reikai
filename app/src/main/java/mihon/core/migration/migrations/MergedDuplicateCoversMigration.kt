package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.dedupe.MergedDuplicate
import reikai.domain.dedupe.MergedDuplicateRepository
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/**
 * Moves the custom cover of each duplicate the upgrade merged away (50.sqm, 51.sqm) to the entry it merged
 * into, since a cover file is named by entry id and the merge moved only database rows. The survivor's own
 * cover wins, and the copy's file goes either way: neither entry table uses AUTOINCREMENT, so the freed id
 * can be handed to a new entry, which would inherit it. Best-effort per file, as the novel cover re-key is.
 * Rule and inventory: docs/dev/plans/mihon-schema-rewrite.md.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class MergedDuplicateCoversMigration(
    private val mergedDuplicates: MergedDuplicateRepository,
    private val coverCache: CoverCache,
) : Migration {
    // Fires once when the shipped versionCode crosses 198 (the version this carry ships in).
    override val version: Float = 198f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val duplicates = runCatching { mergedDuplicates.getAll() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Merged-duplicate covers could not read the record" } }
            .getOrNull()
            ?: return@withIOContext false

        duplicates.forEach(::carryCustomCover)
        mergedDuplicates.clear()
        true
    }

    private fun carryCustomCover(duplicate: MergedDuplicate) {
        val (discarded, survivor) = duplicate.entryIds() ?: return
        val target = coverCache.getCustomCoverFile(survivor)
        customCoverFiles(discarded).filter(File::exists).forEach { file ->
            runCatching {
                if (target.exists()) file.delete() else Files.move(file.toPath(), target.toPath(), ATOMIC_MOVE)
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Merged-duplicate cover carry failed: $discarded to $survivor" }
            }
        }
    }

    // A novel may still sit under its pre-186 name, the negated id: MigrateNovelCustomCoverKeysMigration
    // re-keys only the novels in the table, and on an upgrade from before it the dedupe has already run.
    private fun customCoverFiles(entryId: EntryId): List<File> = when (entryId) {
        is EntryId.Manga -> listOf(coverCache.getCustomCoverFile(entryId))
        is EntryId.Novel -> listOf(
            coverCache.getCustomCoverFile(entryId),
            coverCache.getCustomCoverFile(-entryId.rawId),
        )
    }

    private fun MergedDuplicate.entryIds(): Pair<EntryId, EntryId>? = when (contentType) {
        ContentType.MANGA -> EntryId.Manga(discardedId) to EntryId.Manga(survivorId)
        ContentType.NOVELS -> EntryId.Novel(discardedId) to EntryId.Novel(survivorId)
        ContentType.ALL -> null
    }
}
