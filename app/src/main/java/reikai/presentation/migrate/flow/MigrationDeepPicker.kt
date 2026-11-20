package reikai.presentation.migrate.flow

import androidx.navigation3.runtime.NavKey
import mihon.core.navigation.Navigator
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueRoute

/**
 * Push a full browse of one source to choose a migration target from, when the inline strips are not
 * enough (a title that only turns up behind filters or deeper in the results).
 *
 * One screen for both types: the catalogue's migration-pick mode, where a tap sends the pick to the
 * asking screen's [flowId] and pops. False when the source key does not parse for the type, so the
 * caller can say so rather than push a screen that cannot load.
 */
internal fun openDeepPicker(
    navigator: Navigator,
    entry: MigrationEntry,
    sourceKey: String,
    query: String,
    flowId: Long,
): Boolean {
    val key = when (entry.id) {
        is EntryId.Manga -> SourceKey.Manga(sourceKey.toLongOrNull() ?: return false)
        is EntryId.Novel -> SourceKey.Novel(sourceKey)
    }
    navigator.push(EntryCatalogueRoute(key, query, migrateFor = MigrationPickFor(entry.id, flowId)))
    return true
}
