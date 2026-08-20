package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext

@Inject
@ContributesIntoSet(AppScope::class)
class VerticalNavigatorMigration(
    private val preferenceStore: PreferenceStore,
    private val readerPreferences: ReaderPreferences,
) : Migration {
    // RK: version-gated at Reikai's next release code. Reikai's versionCode sequence is far past
    // Mihon's 25, so upstream's 25f would never fall in an upgrader's (old+1..new) range; this fires
    // once when the shipped versionCode crosses 181.
    override val version: Float = 181f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        // RK: upstream guards `previousVersion == 24` (its single 24->25 bump). Reikai upgraders cross
        // from any prior code, so guard on "this is an upgrade, not a fresh install" instead.
        if (migrationContext.previousVersion != 0) {
            val oldVerticalNavigator = preferenceStore.getBoolean("pref_webtoon_vertical_navigator", true)
            if (oldVerticalNavigator.get()) {
                readerPreferences.verticalNavigator.set(setOf(ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL))
            }
            if (oldVerticalNavigator.isSet()) oldVerticalNavigator.delete()
        }

        val oldVerticalNavigatorOnLeft = preferenceStore.getBoolean("pref_webtoon_vertical_navigator_on_left", false)
        if (oldVerticalNavigatorOnLeft.isSet()) {
            readerPreferences.verticalNavigatorOnLeft.set(oldVerticalNavigatorOnLeft.get())
            oldVerticalNavigatorOnLeft.delete()
        }

        return@withIOContext true
    }
}
