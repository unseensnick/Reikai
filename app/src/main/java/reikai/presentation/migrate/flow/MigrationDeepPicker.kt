package reikai.presentation.migrate.flow

import cafe.adriel.voyager.navigator.Navigator
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueScreen

/**
 * Push a full browse of one source to choose a migration target from, when the inline strips are not
 * enough (a title that only turns up behind filters or deeper in the results).
 *
 * One screen for both types: the catalogue's migration-pick mode, where a tap reports to
 * [MigrationPickHandoff] and pops. False when the source key does not parse for the type, so the
 * caller can say so rather than push a screen that cannot load.
 */
internal fun openDeepPicker(
    navigator: Navigator,
    entry: MigrationEntry,
    sourceKey: String,
    query: String,
): Boolean {
    val key = when (entry.id) {
        is EntryId.Manga -> SourceKey.Manga(sourceKey.toLongOrNull() ?: return false)
        is EntryId.Novel -> SourceKey.Novel(sourceKey)
    }
    navigator.push(EntryCatalogueScreen(key, query, migrateForId = entry.id.rawId))
    return true
}
