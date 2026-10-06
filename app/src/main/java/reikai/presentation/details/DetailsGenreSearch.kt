package reikai.presentation.details

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.Stack
import eu.kanade.tachiyomi.ui.home.HomeScreen
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueScreen

/**
 * A genre tapped on a details page: back to [source]'s own catalogue when it is on the stack, which
 * searches the genre through that source's filters, else a library search for [contentType]. Only
 * that source's catalogue qualifies, since another would search a genre name it may not offer.
 * [filterable] false searches the catalogue as text, for a source with no filters to match.
 */
suspend fun Stack<Screen>.searchGenreFromDetails(
    genre: String,
    source: SourceKey,
    contentType: ContentType,
    filterable: Boolean = true,
) {
    val catalogue = items.catalogueOf(source)
    if (catalogue != null) {
        popUntil { it === catalogue }
        if (filterable) catalogue.searchGenre(genre) else catalogue.search(genre)
        return
    }
    searchLibraryFromDetails(genre, contentType)
}

/**
 * Walks back to the library before searching it for [contentType]: its search channel has no
 * buffer, so a send while the library is off-screen never arrives.
 */
suspend fun Stack<Screen>.searchLibraryFromDetails(query: String, contentType: ContentType) {
    popUntil { it is HomeScreen }
    (lastItemOrNull as? HomeScreen)?.search(query, contentType)
}

/** The nearest catalogue of [source] on the stack, never another source's. */
internal fun List<Screen>.catalogueOf(source: SourceKey): EntryCatalogueScreen? =
    filterIsInstance<EntryCatalogueScreen>().lastOrNull { it.sourceKey == source }
