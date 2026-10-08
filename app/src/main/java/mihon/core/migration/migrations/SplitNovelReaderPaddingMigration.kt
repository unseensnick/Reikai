package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.novel.DEAD_READER_PADDING_KEY
import reikai.domain.novel.NovelPreferences
import reikai.util.carryIfSet
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Carries a customised novel-reader page padding into the margins that replaced it.
 *
 * The old single value padded the page's sides in every build that shipped it, so it becomes the left
 * and right margins, and top and bottom take their new defaults. An untouched install has nothing
 * stored and takes the defaults on every edge.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SplitNovelReaderPaddingMigration(
    private val preferenceStore: PreferenceStore,
    private val novelPreferences: NovelPreferences,
) : Migration {
    // Fires once when the shipped versionCode crosses 191, the version the four margins ship in.
    override val version: Float = 191f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        runCatching {
            preferenceStore.getInt(DEAD_READER_PADDING_KEY, 0)
                .carryIfSet(novelPreferences::carryReaderPaddingToMargins)
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to split the novel reader padding into margins" }
        }

        return@withIOContext true
    }
}
