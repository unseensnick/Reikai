package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.novel.DEAD_READER_TTS_ENABLED_KEY
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Puts the read-aloud button on the novel reader's bottom bar for someone who already used read-aloud.
 *
 * The defaults carry the button, but a bar that was ever customised is stored and never sees a new
 * default, so without this a reader who had read-aloud on would lose every way to reach it in the new
 * reader. Anyone who never turned it on keeps the bar they chose.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class AddReadAloudBottomButtonMigration(
    private val preferenceStore: PreferenceStore,
    private val novelPreferences: NovelPreferences,
) : Migration {
    // RK: fires once when the shipped versionCode crosses 192, the version the read-aloud button ships in.
    override val version: Float = 192f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: defaults apply

        runCatching {
            if (preferenceStore.getBoolean(DEAD_READER_TTS_ENABLED_KEY, false).get()) {
                novelPreferences.addReadAloudButtonToCustomisedBar()
            }
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to add the read-aloud button to the novel reader bar" }
        }

        return@withIOContext true
    }
}
