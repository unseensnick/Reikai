package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.carryShowNsfwSource
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@ContributesIntoSet(AppScope::class)
class ContentWarningMigration(
    private val preferenceStore: PreferenceStore,
    private val sourcePreferences: SourcePreferences,
) : Migration {
    // RK: gated at Reikai's own next versionCode, since upstream's 30f sits below every upgrader's range.
    override val version: Float = 195f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        // RK: the key and the carry are shared with the backup restorer.
        val showNsfwSource = preferenceStore.getBoolean(ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY, true)
        if (!showNsfwSource.isSet()) return true

        sourcePreferences.carryShowNsfwSource(showNsfwSource.get())
        showNsfwSource.delete()

        return true
    }
}
