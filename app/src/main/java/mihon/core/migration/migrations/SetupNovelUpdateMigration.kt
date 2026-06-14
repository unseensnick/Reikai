package mihon.core.migration.migrations

import android.app.Application
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.data.novel.update.NovelUpdateJob

/**
 * RK: schedules the periodic light-novel chapter-update check on every app start (P5 S7), mirroring
 * [SetupLibraryUpdateMigration]. Idempotent: [NovelUpdateJob.setupTask] reads the stored interval and
 * either (re)enqueues the unique periodic work or cancels it when the interval is 0 (off).
 */
class SetupNovelUpdateMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        NovelUpdateJob.setupTask(context)
        return true
    }
}
