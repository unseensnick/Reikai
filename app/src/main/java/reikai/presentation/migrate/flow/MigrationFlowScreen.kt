package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import mihon.app.di.appGraph
import reikai.domain.library.ContentType

/**
 * Marker for the flow's intermediate steps (entry pick, source config, favorites, search, list). A
 * finished migration unwinds to the first screen [isOutsideMigrationFlow], landing back on whatever
 * launched the flow: a single pop would land on the screen below, which is a stale step of a flow that
 * already completed.
 */
interface MigrationFlowRoute

/** Whether [screen] is outside the migration flow. */
fun isOutsideMigrationFlow(screen: NavKey): Boolean = screen !is MigrationFlowRoute

/**
 * The flow's adapter for [contentType], read off the graph once per composition. Every flow model
 * takes it as a constructor argument rather than resolving it, so a JVM test can hand in a fake.
 */
@Composable
internal fun rememberMigrationAdapter(contentType: ContentType): MigrationFlowAdapter {
    val context = LocalContext.current
    return remember(contentType) { context.appGraph.migrationAdapters.forType(contentType) }
}
