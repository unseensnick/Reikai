package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.data.dedupe.MergedDuplicateDownloads
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
 * Carries what each duplicate the upgrade merged away (50.sqm, 51.sqm) kept outside the database to the entry it
 * merged into. Here, before anything reads them: its custom cover and its queued downloads, keyed by the freed id,
 * which a new entry can be handed since neither entry table uses AUTOINCREMENT. The survivor's own cover wins, and
 * the copy's file goes either way. Its download folders merge by copy, which here would hold the main thread that
 * MainActivity blocks on the migrations, so [carryFolders] does them afterwards and then empties the record.
 * Best-effort per file. Rules and inventory: docs/dev/plans/mihon-schema-rewrite.md.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class MergedDuplicateCarryMigration(
    private val mergedDuplicates: MergedDuplicateRepository,
    private val coverCache: CoverCache,
    // Deferred: App reads this at every launch, and building the carry starts the novel download index's scan
    private val downloads: () -> MergedDuplicateDownloads,
) : Migration {
    // Fires once when the shipped versionCode crosses 198 (the version this carry ships in).
    override val version: Float = 198f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val duplicates = runCatching { mergedDuplicates.getAll() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Merged-duplicate carry could not read the record" } }
            .getOrNull()
            ?: return@withIOContext false

        duplicates.forEach(::carryCustomCover)
        runCatching { downloads().remapQueues(duplicates, mergedDuplicates.getChapters()) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Merged-duplicate download queue carry failed" } }
        // No merged entry means no folder to carry, and nothing after this migration reads the chapter rows
        if (duplicates.isEmpty()) mergedDuplicates.clear()
        true
    }

    /**
     * Merges the merged-away copies' download folders into the survivors', on every launch until none is left (no
     * room, a failed copy), then empties the record. Folders only: a cover and a queued download are keyed by the
     * freed id, which a new entry may hold once the migration has run.
     */
    suspend fun carryFolders() = withIOContext {
        val duplicates = runCatching { mergedDuplicates.getAll() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Merged-duplicate carry could not read the record" } }
            .getOrNull()
        if (duplicates.isNullOrEmpty()) return@withIOContext
        val finished = runCatching { downloads().carryFolders(duplicates) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Merged-duplicate folder carry failed" } }
            .getOrDefault(false)
        if (finished) mergedDuplicates.clear()
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
