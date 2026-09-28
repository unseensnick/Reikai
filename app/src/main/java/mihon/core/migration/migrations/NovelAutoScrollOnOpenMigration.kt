package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.novel.DEAD_READER_AUTO_SCROLL_KEY
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Carries the novel reader's auto-scroll switch, which was whether it ran, into start-on-open, so a
 * reader who left it on still has it start. An untouched switch stored nothing and keeps the default.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class NovelAutoScrollOnOpenMigration(
    private val preferenceStore: PreferenceStore,
    private val novelPreferences: NovelPreferences,
) : Migration {
    // Fires once when the shipped versionCode crosses 197, the version the split ships in.
    override val version: Float = 197f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: nothing stored

        runCatching {
            val running = preferenceStore.getBoolean(DEAD_READER_AUTO_SCROLL_KEY, false)
            if (!running.isSet()) return@runCatching

            novelPreferences.carryReaderAutoScroll(running.get())
            running.delete()
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to carry the novel auto-scroll switch into start-on-open" }
        }

        return@withIOContext true
    }
}
