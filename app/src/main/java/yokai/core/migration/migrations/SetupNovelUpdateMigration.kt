package yokai.core.migration.migrations

import android.app.Application
import eu.kanade.tachiyomi.data.library.NovelUpdateJob
import yokai.core.migration.Migration
import yokai.core.migration.MigrationContext

class SetupNovelUpdateMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        NovelUpdateJob.setupTask(context)
        return true
    }
}
