package reikai.presentation.migrate.flow

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.Stack
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueScreen

/**
 * Push a full browse of one source to choose a migration target from, when the inline strips are not
 * enough (a title that only turns up behind filters or deeper in the results).
 *
 * One screen for both types: the catalogue's migration-pick mode, where a tap sends the pick to the
 * asking screen's [flowId] and pops. False when the source key does not parse for the type, so the
 * caller can say so rather than push a screen that cannot load.
 */
internal fun openDeepPicker(
    navigator: Stack<Screen>,
    entry: MigrationEntry,
    sourceKey: String,
    query: String,
    flowId: Long,
): Boolean {
    val key = when (entry.id) {
        is EntryId.Manga -> SourceKey.Manga(sourceKey.toLongOrNull() ?: return false)
        is EntryId.Novel -> SourceKey.Novel(sourceKey)
    }
    navigator.push(EntryCatalogueScreen(key, query, migrateFor = MigrationPickFor(entry.id, flowId)))
    return true
}
