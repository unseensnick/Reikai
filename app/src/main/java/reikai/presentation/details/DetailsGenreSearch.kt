package reikai.presentation.details

import androidx.navigation3.runtime.result.ResultEventBus
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.Stack
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.home.TabEvent
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.BrowseSearchEvent
import reikai.presentation.browse.catalogue.EntryCatalogueScreen

/**
 * A genre tapped on a details page: back to [source]'s own catalogue when it is on the stack, which
 * searches the genre through that source's filters, else a library search for [contentType]. Only
 * that source's catalogue qualifies, since another would search a genre name it may not offer.
 * [filterable] false searches the catalogue as text, for a source with no filters to match.
 */
fun Stack<Screen>.searchGenreFromDetails(
    bus: ResultEventBus,
    genre: String,
    source: SourceKey,
    contentType: ContentType,
    filterable: Boolean = true,
) {
    val catalogue = items.catalogueOf(source)
    if (catalogue != null) {
        popUntil { it === catalogue }
        bus.sendResult(BrowseSearchEvent(genre, genre = filterable))
        return
    }
    searchLibraryFromDetails(bus, genre, contentType)
}

/**
 * Walks back to the library before searching it for [contentType], past any catalogue on the way, so
 * the search always reaches the library and is shown there.
 */
fun Stack<Screen>.searchLibraryFromDetails(bus: ResultEventBus, query: String, contentType: ContentType) =
    showHome(bus, TabEvent.Library(query = query, contentType = contentType))

/** Walks back to the library tab, after a details page changed its filter. */
fun Stack<Screen>.showLibraryFromDetails(bus: ResultEventBus) = showHome(bus, TabEvent.Library())

// Sent only once Home is on top: it is the event's one reader, and a send it never collects would sit
// on the bus for the next time Home shows.
private fun Stack<Screen>.showHome(bus: ResultEventBus, event: TabEvent) {
    popUntil { it is HomeScreen }
    if (lastItemOrNull is HomeScreen) bus.sendResult<TabEvent>(event)
}

/** The nearest catalogue of [source] on the stack, never another source's. */
internal fun List<Screen>.catalogueOf(source: SourceKey): EntryCatalogueScreen? =
    filterIsInstance<EntryCatalogueScreen>().lastOrNull { it.sourceKey == source }
