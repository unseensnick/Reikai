package reikai.presentation.details

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.Stack
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesRoute
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.browse.globalsearch.EntryGlobalSearchScreen
import reikai.presentation.migrate.flow.EntryMigrationSourcePickScreen
import reikai.presentation.novel.notes.NovelNotesScreen

/** The viewed member's source, which a genre on the page searches and the header's source row browses. */
data class DetailsViewedSource(
    val key: SourceKey,
    /** False for a local or uninstalled source, which has no catalogue to open. */
    val browsable: Boolean,
    /** False for a source with no filters, whose catalogue searches a genre as text. */
    val filterable: Boolean = true,
)

/**
 * The pushes a details page makes the same way for both types, filled from the page's own state, so a
 * screen states only which source it views. Anchor-scoped where the series is re-homed (Migrate, which opens
 * the source picker so a merged series picks its source), and the viewed member's where the page shows one
 * (notes, a genre, the source row).
 */
internal fun EntryDetailsNavigation.withSharedPushes(
    state: EntryDetailsScreenState.Loaded,
    stack: Stack<Screen>,
    scope: CoroutineScope,
): EntryDetailsNavigation {
    val type = state.entryId.contentType
    return copy(
        onGlobalSearch = { stack.push(EntryGlobalSearchScreen(it, scopedContentType = type)) },
        onLibrarySearch = { scope.launch { stack.searchLibraryFromDetails(it, type) } },
        onTagSearch = { genre ->
            scope.launch { stack.searchGenreFromDetails(genre, viewedSource.key, type, viewedSource.filterable) }
        },
        onBrowseSource = { stack.push(EntryCatalogueScreen(viewedSource.key)) }.takeIf { viewedSource.browsable },
        onOpenTrackingSettings = { stack.push(SettingsScreen(SettingsScreen.Destination.Tracking)) },
        onEditNotes = {
            stack.push(
                when (val viewed = EntryId.of(type, state.viewedEntryId)) {
                    is EntryId.Manga -> MangaNotesRoute(viewed.rawId)
                    is EntryId.Novel -> NovelNotesScreen(viewed.rawId)
                },
            )
        },
        onMigrate = { stack.push(EntryMigrationSourcePickScreen(type, listOf(state.entryId.rawId))) },
    )
}
