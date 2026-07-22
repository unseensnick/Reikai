package mihon.core.migration.migrations

import eu.kanade.tachiyomi.data.cache.CoverCache
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * One-time move of user-set novel covers onto the namespaced file name.
 *
 * Manga and novels share one custom-cover directory, and a novel's file used to be named by its negated
 * id purely so it could not collide with a same-id manga. Now the name carries the content type, so the
 * old files have to move or every custom novel cover would read as missing and silently fall back to the
 * source's cover. Manga names are unchanged, so only novels are touched.
 *
 * Copy then delete, best-effort per novel: a failure leaves the old file in place rather than losing a
 * cover the user set by hand, and re-running the migration picks it up.
 */
class MigrateNovelCustomCoverKeysMigration : Migration {
    // RK: fires once when the shipped versionCode crosses 186 (the version this re-key ships in).
    override val version: Float = 186f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: no covers yet
        val novelRepository = migrationContext.get<NovelRepository>() ?: return@withIOContext false
        val coverCache = migrationContext.get<CoverCache>() ?: return@withIOContext false

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
                if (!targetFile.exists()) {
                    legacyFile.inputStream().use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                legacyFile.delete()
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Novel cover re-key failed: novel=${novel.id}" }
            }
        }
        true
    }
}
