package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
@ContributesIntoSet(AppScope::class)
class ChapterNameSuffixMigration(
    private val preference: LibraryPreferences,
) : Migration {
    // RK: upstream's 34f sits below every Reikai versionCode, so no upgrade would run it
    override val version: Float = 199f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        // RK: upstream guards its versions 13..33. Every earlier Reikai build named with the hash, and a fresh
        // install never runs a version-gated migration (InitialMigrationStrategy), so every run here is an upgrade.
        preference.enableChapterNameHash.set(true)
        return true
    }
}
