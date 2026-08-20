package mihon.core.migration.migrations

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.data.novel.update.NovelUpdateJob

/**
 * RK: schedules the periodic light-novel chapter-update check on every app start, mirroring
 * [SetupLibraryUpdateMigration]. Idempotent: [NovelUpdateJob.setupTask] reads the stored interval and
 * either (re)enqueues the unique periodic work or cancels it when the interval is 0 (off).
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SetupNovelUpdateMigration(
    private val context: Context,
) : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        NovelUpdateJob.setupTask(context)
        return true
    }
}
