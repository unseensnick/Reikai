package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.novel.DEAD_READER_TAP_TO_SCROLL_KEY
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Carries the novel reader's tap-to-scroll switch into the tap layout that replaced it, so a reader who
 * had it on keeps tapping the top and bottom thirds. An untouched switch stored nothing and takes the
 * new default, which behaves as the switch's own default did.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class NovelTapLayoutMigration(
    private val preferenceStore: PreferenceStore,
    private val novelPreferences: NovelPreferences,
) : Migration {
    // RK: fires once when the shipped versionCode crosses 194, the version the tap layouts ship in.
    override val version: Float = 194f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: nothing stored

        runCatching {
            val tapToScroll = preferenceStore.getBoolean(DEAD_READER_TAP_TO_SCROLL_KEY, false)
            if (!tapToScroll.isSet()) return@runCatching

            novelPreferences.carryReaderTapToScroll(tapToScroll.get())
            tapToScroll.delete()
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to carry the novel tap-to-scroll switch into a tap layout" }
        }

        return@withIOContext true
    }
}
