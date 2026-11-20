package reikai.presentation.details

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.ResultEventBus
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.openSettings
import mihon.core.navigation.MangaNotesRoute
import mihon.core.navigation.Navigator
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.browse.globalsearch.EntryGlobalSearchRoute
import reikai.presentation.migrate.flow.EntryMigrationSourcePickRoute
import reikai.presentation.novel.notes.NovelNotesRoute
import reikai.presentation.track.EntryTrackSheetRoute

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
    /** The Tracking tap with no tracker logged in, to log in to one. */
    val onOpenTrackingSettings: () -> Unit,
    /** The Tracking tap once a tracker is logged in. */
    val onOpenTrackSheet: () -> Unit,
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
    stack: Navigator,
    bus: ResultEventBus,
    /** Whether settings open beside their list, as a tablet's do. */
    twoPane: Boolean,
): SharedDetailsPushes {
    val type = state.entryId.contentType
    return SharedDetailsPushes(
        onGlobalSearch = { stack.push(EntryGlobalSearchRoute(it, scopedContentType = type)) },
        onLibrarySearch = { stack.searchLibraryFromDetails(bus, it, type) },
        onTagSearch = { genre ->
            stack.searchGenreFromDetails(bus, genre, viewedSource.key, type, viewedSource.filterable)
        },
        onBrowseSource = { stack.push(EntryCatalogueRoute(viewedSource.key)) }.takeIf { viewedSource.browsable },
        onOpenTrackingSettings = { stack.openSettings(SettingsTrackingScreen, twoPane) },
        onOpenTrackSheet = { stack.push(EntryTrackSheetRoute(state.entryId)) },
        onEditNotes = {
            stack.push(
                when (val viewed = EntryId.of(type, state.viewedEntryId)) {
                    is EntryId.Manga -> MangaNotesRoute(viewed.rawId)
                    is EntryId.Novel -> NovelNotesRoute(viewed.rawId)
                },
            )
        },
        onMigrate = { stack.push(EntryMigrationSourcePickRoute(type, listOf(state.entryId.rawId))) },
    )
}
