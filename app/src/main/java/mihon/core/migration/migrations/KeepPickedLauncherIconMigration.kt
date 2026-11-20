package mihon.core.migration.migrations

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.util.keepPickedLauncherIcon
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Keeps an app icon picked before this update as the only one: the update moves the build's own icon back onto the
 * entry older versions had, which starts on, and it would otherwise show beside the pick.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class KeepPickedLauncherIconMigration(
    private val context: Context,
) : Migration {
    // Fires once when the shipped versionCode crosses 211, the version the entry moves in.
    override val version: Float = 211f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        runCatching { context.keepPickedLauncherIcon() }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to keep the picked app icon" }
        }

        return@withIOContext true
    }
}
