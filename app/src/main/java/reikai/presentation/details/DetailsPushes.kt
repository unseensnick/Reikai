package reikai.presentation.details

import androidx.navigation3.runtime.result.ResultEventBus
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.Stack
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesRoute
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
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
 * The pushes a details page makes the same way for both types, filled from the page's own state by
 * [withSharedPushes] and never by a screen, so a screen states only which source it views.
 */
internal data class SharedDetailsPushes(
    /** Header title / author / artist tap: a global search, scoped to this entry's type. */
    val onGlobalSearch: (query: String) -> Unit,
    /** Header long-press: search the library, forced to this entry's type. The source row passes a
     *  `srcid:` query for its source rather than the name it shows. */
    val onLibrarySearch: (query: String) -> Unit,
    val onTagSearch: (genre: String) -> Unit,
    /** The header source row; null when nothing can be browsed. */
    val onBrowseSource: (() -> Unit)?,
    /** The Tracking tap with no tracker logged in; with one, the body opens the track sheet itself. */
    val onOpenTrackingSettings: () -> Unit,
    val onEditNotes: () -> Unit,
    /** Library entries only ([forState]). */
    val onMigrate: (() -> Unit)?,
)

/** What the body's parts read: the screen's own navigation, the shared pushes, and two taps on the body's state. */
internal data class DetailsPageNav(
    val perType: EntryDetailsNavigation,
    val shared: SharedDetailsPushes,
    /** A tag's Show only / Hide this tag, which set the library's tag filter. */
    val onFilterLibraryByTag: (tag: String, show: Boolean) -> Unit,
    /** Opens the cover on screen, a picked volume's own. */
    val onCoverClick: () -> Unit,
)

/**
 * The shared pushes, anchor-scoped where the series is re-homed (Migrate, which opens the source picker so a merged series
 * picks its source), and the viewed member's where the page shows one (notes, a genre, the source row).
 */
internal fun EntryDetailsNavigation.withSharedPushes(
    state: EntryDetailsScreenState.Loaded,
    stack: Stack<Screen>,
    bus: ResultEventBus,
): SharedDetailsPushes {
    val type = state.entryId.contentType
    return SharedDetailsPushes(
        onGlobalSearch = { stack.push(EntryGlobalSearchScreen(it, scopedContentType = type)) },
        onLibrarySearch = { stack.searchLibraryFromDetails(bus, it, type) },
        onTagSearch = { genre ->
            stack.searchGenreFromDetails(bus, genre, viewedSource.key, type, viewedSource.filterable)
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
