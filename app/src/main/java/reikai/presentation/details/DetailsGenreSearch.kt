package reikai.presentation.details

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.ResultEventBus
import eu.kanade.tachiyomi.ui.home.TabEvent
import mihon.core.navigation.HomeRoute
import mihon.core.navigation.Navigator
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.BrowseSearchEvent
import reikai.presentation.browse.catalogue.EntryCatalogueRoute

/**
 * A genre tapped on a details page: back to [source]'s own catalogue when it is on the stack, which
 * searches the genre through that source's filters, else a library search for [contentType]. Only
 * that source's catalogue qualifies, since another would search a genre name it may not offer.
 * [filterable] false searches the catalogue as text, for a source with no filters to match.
 */
fun Navigator.searchGenreFromDetails(
    bus: ResultEventBus,
    genre: String,
    source: SourceKey,
    contentType: ContentType,
    filterable: Boolean = true,
) {
    val catalogue = routes.catalogueOf(source)
    if (catalogue != null) {
        popTo { it === catalogue }
        bus.sendResult(BrowseSearchEvent(genre, genre = filterable))
        return
    }
    searchLibraryFromDetails(bus, genre, contentType)
}

/**
 * Walks back to the library before searching it for [contentType], past any catalogue on the way, so
 * the search always reaches the library and is shown there.
 */
fun Navigator.searchLibraryFromDetails(bus: ResultEventBus, query: String, contentType: ContentType) =
    showHome(bus, TabEvent.Library(query = query, contentType = contentType))

/** Walks back to the library tab, after a details page changed its filter. */
fun Navigator.showLibraryFromDetails(bus: ResultEventBus) = showHome(bus, TabEvent.Library())

// Home is TabEvent's one reader, and reads it only while shown: a send over a screen covering Home applies
// when Home shows again, as a shortcut's does. Here Home is brought to the top first, so the search applies
// at once, and with no Home below nothing is sent.
private fun Navigator.showHome(bus: ResultEventBus, event: TabEvent) {
    popTo { it is HomeRoute }
    if (current is HomeRoute) bus.sendResult<TabEvent>(event)
}

/** The nearest catalogue of [source] on the stack, never another source's. */
internal fun List<NavKey>.catalogueOf(source: SourceKey): EntryCatalogueRoute? =
    filterIsInstance<EntryCatalogueRoute>().lastOrNull { it.sourceKey == source }
