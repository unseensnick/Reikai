package mihon.core.migration.migrations

import android.app.Application
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.data.novel.update.LnPluginUpdateJob

/**
 * RK: schedules the periodic light-novel plugin update check on every app start, mirroring
 * [SetupLibraryUpdateMigration]. Idempotent via [LnPluginUpdateJob.setupTask]'s unique-work policy.
 */
class SetupLnPluginUpdateMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        LnPluginUpdateJob.setupTask(context)
        return true
    }
}
