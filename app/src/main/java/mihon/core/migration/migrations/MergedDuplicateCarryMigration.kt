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
 * merged into: its custom cover, named by entry id, and its downloads, named by title and queued by id. The
 * survivor's own cover wins, and the copy's file goes either way: neither entry table uses AUTOINCREMENT, so the
 * freed id can be handed to a new entry, which would inherit it. Best-effort per file, as the novel cover re-key
 * is. The one reader that empties the record, which it does only once every download folder is merged, here or
 * by [retryUnfinishedFolders] on a later launch. Rules and inventory: docs/dev/plans/mihon-schema-rewrite.md.
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
        val finished = runCatching { downloads().carry(duplicates, mergedDuplicates.getChapters()) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Merged-duplicate download carry failed" } }
            .getOrDefault(false)
        if (finished) mergedDuplicates.clear()
        true
    }

    /**
     * Tries again, on each later launch, the download folders the upgrade could not finish merging (no room, a failed
     * copy), and empties the record once none is left. Folders only: a cover and a queued download are keyed by the
     * freed id, which a new entry may hold by now.
     */
    suspend fun retryUnfinishedFolders() = withIOContext {
        val duplicates = runCatching { mergedDuplicates.getAll() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Merged-duplicate carry could not read the record" } }
            .getOrNull()
        if (duplicates.isNullOrEmpty()) return@withIOContext
        val finished = runCatching { downloads().carryFolders(duplicates) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Merged-duplicate folder retry failed" } }
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
