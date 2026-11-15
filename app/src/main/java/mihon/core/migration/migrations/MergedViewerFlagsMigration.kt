package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.library.ContentType
import reikai.domain.merge.GatherGroupViewerFlags
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * One-time gathering of reader flags a merged series kept on whichever member a reader opened
 * through, before both readers read them from the group. Best-effort per content type, so a failure
 * on one side does not block startup or the other side.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class MergedViewerFlagsMigration(
    private val gatherGroupViewerFlags: GatherGroupViewerFlags,
) : Migration {
    override val version: Float = 209f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        listOf(ContentType.MANGA, ContentType.NOVELS).forEach { type ->
            runCatching { gatherGroupViewerFlags.gather(type) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Gathering merged $type reader flags failed" } }
        }
        true
    }
}
