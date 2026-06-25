package mihon.core.migration.migrations

import android.app.Application
import exh.eh.EHentaiUpdateWorker
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext

/**
 * RK: schedules the periodic E-Hentai favorited-gallery update check on every app start, mirroring
 * [SetupNovelUpdateMigration]. Idempotent: [EHentaiUpdateWorker.setupTask] reads the stored interval
 * and either (re)enqueues the unique periodic work or cancels it when the interval is 0 (off).
 */
class SetupEHentaiUpdateMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        EHentaiUpdateWorker.setupTask(context)
        return true
    }
}
