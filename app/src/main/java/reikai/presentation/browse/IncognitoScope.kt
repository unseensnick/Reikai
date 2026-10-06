package reikai.presentation.browse

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.novel.details.NovelScreen

/** A source's catalogue, or a details page opened from one, is closed when incognito mode ends. */
fun Screen.closesWhenIncognitoEnds(): Boolean = when (this) {
    is EntryCatalogueScreen -> true
    is MangaScreen -> fromSource
    is NovelScreen -> fromSource
    else -> false
}
