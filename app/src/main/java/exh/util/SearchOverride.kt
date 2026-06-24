package exh.util

import android.content.Context
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import exh.GalleryAddEvent
import exh.GalleryAdder

private val galleryAdder by lazy { GalleryAdder() }

/**
 * A version of getSearchManga that, when the query is a gallery URL, resolves it into the matching
 * manga via [GalleryAdder] instead of running a normal source search.
 */
suspend fun UrlImportableSource.urlImportFetchSearchMangaSuspend(
    context: Context,
    query: String,
    fail: suspend () -> MangasPage,
): MangasPage = when {
    query.startsWith("http://") || query.startsWith("https://") -> {
        val res = galleryAdder.addGallery(context = context, url = query, fav = false, forceSource = this)
        MangasPage(
            if (res is GalleryAddEvent.Success) listOf(res.manga.toSManga()) else emptyList(),
            false,
        )
    }
    else -> fail()
}
