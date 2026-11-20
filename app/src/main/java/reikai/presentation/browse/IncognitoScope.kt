package reikai.presentation.browse

import androidx.navigation3.runtime.NavKey
import mihon.core.navigation.MangaRoute
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.novel.details.NovelRoute

/** A source's catalogue, or a details page opened from one, is closed when incognito mode ends. */
fun NavKey.closesWhenIncognitoEnds(): Boolean = when (this) {
    is EntryCatalogueRoute -> true
    is MangaRoute -> fromSource
    is NovelRoute -> fromSource
    else -> false
}

/** The source whose own incognito setting applies while this is shown: a catalogue's, else none. */
fun NavKey.incognitoSource(): SourceKey? = (this as? EntryCatalogueRoute)?.sourceKey
