package reikai.presentation.browse

import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.novel.details.NovelRoute

// Over Any, so the same rules read a Voyager screen and a Navigation 3 route.

/** A source's catalogue, or a details page opened from one, is closed when incognito mode ends. */
fun Any?.closesWhenIncognitoEnds(): Boolean = when (this) {
    is EntryCatalogueRoute -> true
    is MangaScreen -> fromSource
    is NovelRoute -> fromSource
    else -> false
}

/** The source whose own incognito setting applies while this is shown: a catalogue's, else none. */
fun Any?.incognitoSource(): SourceKey? = (this as? EntryCatalogueRoute)?.sourceKey
