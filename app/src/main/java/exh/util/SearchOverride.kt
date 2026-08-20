package exh.util

import android.content.Context
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import exh.GalleryAddEvent
import exh.GalleryAdder
import mihon.app.di.appGraph

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
        // Built per call rather than once per process: the adder snapshots the enabled-language and
        // disabled-source preferences at construction, so a cached one would go stale.
        val res = context.appGraph.galleryAdder
            .addGallery(context = context, url = query, fav = false, forceSource = this)
        MangasPage(
            if (res is GalleryAddEvent.Success) listOf(res.manga.toSManga()) else emptyList(),
            false,
        )
    }
    else -> fail()
}
