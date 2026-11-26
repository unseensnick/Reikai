package mihon.core.migration.migrations

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.library.DynamicGroupKeys
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Gives each collapsed status, tracking status, Unknown, Not tracked and Ungrouped group its
 * language-neutral key, so changing the language no longer opens it again. Reads the labels in the app's
 * language at upgrade time, the one the keys were saved under.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class CollapsedGroupKeysMigration(
    private val context: Context,
    private val libraryPreferences: ReikaiLibraryPreferences,
) : Migration {
    override val version: Float = 211f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        runCatching {
            val collapsed = libraryPreferences.collapsedDynamicCategories
            collapsed.set(DynamicGroupKeys.withNeutralKeys(collapsed.get(), context))
        }.onFailure { logcat(LogPriority.ERROR, it) { "Failed to give collapsed groups neutral keys" } }
        true
    }
}
