package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.data.ChapterNameFoldBackfill

/**
 * Folds the names of chapters stored before 88.sqm, so the library's `chapter:` search finds them in any case.
 * Launched on the app scope rather than awaited, since startup waits on the chain and a library of non-ASCII names
 * takes seconds; a row it has not reached yet is searched as before.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class ChapterNameFoldMigration(
    private val backfill: ChapterNameFoldBackfill,
    @AppCoroutineScope private val appScope: CoroutineScope,
) : Migration {
    override val version: Float = 212f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        appScope.launch { backfill.run() }
        return true
    }
}
