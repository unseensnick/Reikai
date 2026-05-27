package yokai.core.migration.migrations

import android.app.Application
import yokai.core.migration.Migration
import yokai.core.migration.MigrationContext
import yokai.novel.update.LnPluginUpdateJob

class SetupLnPluginUpdateMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        LnPluginUpdateJob.setupTask(context)
        return true
    }
}
