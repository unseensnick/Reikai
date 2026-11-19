package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.migration.models.MigrationFlag
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelMigrationFlag
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Turns on the new custom info migration option for someone who already saved their migration options,
 * as every option starts on. A stored mask never sees a new default, so without this the option would
 * start off only for them. An unstored mask already reads the default, which carries the option.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class MigrationCustomInfoFlagMigration(
    private val sourcePreferences: SourcePreferences,
    private val novelPreferences: NovelPreferences,
) : Migration {
    // Fires once when the shipped versionCode crosses 210, the version the option ships in.
    override val version: Float = 210f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        runCatching {
            val manga = sourcePreferences.migrationFlags
            if (manga.isSet()) manga.set(manga.get() + MigrationFlag.CUSTOM_INFO)
            val novels = novelPreferences.novelMigrationFlags()
            if (novels.isSet()) novels.set(novels.get() or NovelMigrationFlag.CUSTOM_INFO.bit)
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to turn on the custom info migration option" }
        }

        return@withIOContext true
    }
}
