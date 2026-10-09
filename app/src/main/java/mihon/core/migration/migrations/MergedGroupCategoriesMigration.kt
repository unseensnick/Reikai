package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.library.ContentType
import reikai.domain.merge.AlignGroupCategories
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * One-time fix for merged series whose members were filed in different categories before a category
 * write reached the whole group: each group takes its first library member's categories. A preference
 * migration rather than a schema one, since the owner comes from the group logic, not from SQL.
 * Best-effort per content type, so a failure on one side does not block startup or the other side.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class MergedGroupCategoriesMigration(
    private val alignGroupCategories: AlignGroupCategories,
) : Migration {
    override val version: Float = 200f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        listOf(ContentType.MANGA, ContentType.NOVELS).forEach { type ->
            runCatching { alignGroupCategories.align(type) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Aligning merged group categories failed for $type" } }
        }
        true
    }
}
