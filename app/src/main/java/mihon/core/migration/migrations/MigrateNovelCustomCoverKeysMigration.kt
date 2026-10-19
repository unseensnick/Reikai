package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.data.cache.legacyNovelCustomCoverFile
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/**
 * One-time move of user-set novel covers onto the namespaced file name.
 *
 * Manga and novels share one custom-cover directory; novel files used to be named by negated id, and
 * without this move every custom novel cover would read as missing. Manga names are unchanged.
 * Best-effort: one atomic move per novel within one directory, so a file is never half-copied; a failure
 * is only logged and that cover falls back to the source's.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class MigrateNovelCustomCoverKeysMigration(
    private val novelRepository: NovelRepository,
    private val coverCache: CoverCache,
) : Migration {
    // Fires once when the shipped versionCode crosses 186 (the version this re-key ships in).
    override val version: Float = 186f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val novels = runCatching { novelRepository.getAll() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Novel cover re-key could not read the novels" } }
            .getOrNull()
            ?: return@withIOContext false

        novels.forEach { novel ->
            val legacyFile = coverCache.legacyNovelCustomCoverFile(novel.id)
            if (!legacyFile.exists()) return@forEach

            val targetFile = coverCache.getCustomCoverFile(EntryId.Novel(novel.id))
            runCatching {
                // An atomic rename replaces an existing target, which can only be a partial copy an older
                // build left, so the user's cover wins.
                Files.move(legacyFile.toPath(), targetFile.toPath(), ATOMIC_MOVE)
                coverCache.reindexCustomCovers(legacyFile, targetFile)
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Novel cover re-key failed: novel=${novel.id}" }
            }
        }
        true
    }
}
