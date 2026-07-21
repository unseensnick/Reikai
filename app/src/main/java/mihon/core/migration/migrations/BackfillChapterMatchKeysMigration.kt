package mihon.core.migration.migrations

import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.merge.ReconcileChapterMatchKeys
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Fills in the cross-source chapter identities behind a merged entry's deduplicated unread count, for
 * installs that already have merged series and chapters on disk.
 *
 * This is not backfill-specific code: it runs the same reconciliation that keeps the identities
 * current afterwards. On a database that has never had them, every chapter of a merged entry reads as
 * outdated, so one pass fills them all.
 *
 * It has to run before the count is shown, because empty tables do not read as "no data": every
 * chapter would fall back to counting on its own and a merged entry would report the summed-across-
 * sources total instead of the deduplicated one. Best-effort, so a failure does not block startup;
 * the same reconciliation runs after the next library update and will fill in what was missed.
 */
class BackfillChapterMatchKeysMigration : Migration {
    // RK: fires once when the shipped versionCode crosses 185 (the version this ships in).
    override val version: Float = 185f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: no chapters yet
        val reconcile = migrationContext.get<ReconcileChapterMatchKeys>() ?: return@withIOContext false

        runCatching { reconcile.await() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Failed to backfill chapter match keys" } }

        return@withIOContext true
    }
}
