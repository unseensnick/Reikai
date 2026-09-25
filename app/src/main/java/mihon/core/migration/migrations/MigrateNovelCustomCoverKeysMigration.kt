package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/**
 * One-time move of user-set novel covers onto the namespaced file name.
 *
 * Manga and novels share one custom-cover directory, and a novel's file used to be named by its negated
 * id purely so it could not collide with a same-id manga. Now the name carries the content type, so the
 * old files have to move or every custom novel cover would read as missing and silently fall back to the
 * source's cover. Manga names are unchanged, so only novels are touched.
 *
 * One atomic move per novel, best-effort: both names sit in the same directory, so the file is either
 * wholly renamed or left where it was, never half-copied. A failure is only logged and not retried,
 * so a cover it could not move falls back to the source's.
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
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: no covers yet

        val novels = runCatching { novelRepository.getAll() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Novel cover re-key could not read the novels" } }
            .getOrNull()
            ?: return@withIOContext false

        novels.forEach { novel ->
            // The retired name: the Long-keyed overload over the negated id.
            val legacyFile = coverCache.getCustomCoverFile(-novel.id)
            if (!legacyFile.exists()) return@forEach

            val targetFile = coverCache.getCustomCoverFile(EntryId.Novel(novel.id))
            runCatching {
                // An atomic rename replaces an existing target, which can only be a partial copy an older
                // build left, so the user's cover wins.
                Files.move(legacyFile.toPath(), targetFile.toPath(), ATOMIC_MOVE)
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Novel cover re-key failed: novel=${novel.id}" }
            }
        }
        true
    }
}
