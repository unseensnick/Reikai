package mihon.core.migration.migrations

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import exh.eh.EHentaiUpdateWorker
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext

/**
 * RK: schedules the periodic E-Hentai favorited-gallery update check on every app start, mirroring
 * [SetupNovelUpdateMigration]. Idempotent: [EHentaiUpdateWorker.setupTask] reads the stored interval
 * and either (re)enqueues the unique periodic work or cancels it when the interval is 0 (off).
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SetupEHentaiUpdateMigration(
    private val context: Context,
) : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        EHentaiUpdateWorker.setupTask(context)
        return true
    }
}
