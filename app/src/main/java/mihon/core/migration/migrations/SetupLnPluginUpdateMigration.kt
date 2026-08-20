package mihon.core.migration.migrations

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.data.novel.update.LnPluginUpdateJob

/**
 * RK: schedules the periodic light-novel plugin update check on every app start, mirroring
 * [SetupLibraryUpdateMigration]. Idempotent via [LnPluginUpdateJob.setupTask]'s unique-work policy.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SetupLnPluginUpdateMigration(
    private val context: Context,
) : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        LnPluginUpdateJob.setupTask(context)
        return true
    }
}
