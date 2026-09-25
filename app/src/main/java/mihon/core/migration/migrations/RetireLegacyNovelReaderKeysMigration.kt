package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.novel.DEAD_READER_TTS_BUTTON_KEYS
import reikai.domain.novel.DEAD_READER_TTS_ENABLED_KEY
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Deletes the keys only the retired standalone novel reader wrote, so they stop riding along in
 * backups. Runs after [AddReadAloudBottomButtonMigration], which still reads the read-aloud switch.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class RetireLegacyNovelReaderKeysMigration(
    private val preferenceStore: PreferenceStore,
) : Migration {
    // Fires once when the shipped versionCode crosses 193, the version the old reader is removed in.
    override val version: Float = 193f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: nothing stored

        runCatching {
            preferenceStore.getBoolean(DEAD_READER_TTS_ENABLED_KEY, false).delete()
            DEAD_READER_TTS_BUTTON_KEYS.forEach { preferenceStore.getInt(it, Int.MIN_VALUE).delete() }
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to delete the retired novel reader keys" }
        }

        return@withIOContext true
    }
}
