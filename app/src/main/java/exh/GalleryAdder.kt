package exh

import android.content.Context
import androidx.core.net.toUri
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.copyFrom
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import exh.source.getMainSource
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Resolves a pasted gallery URL into a local manga by routing it to the matching enhanced source.
 *
 * Lean port of Komikku's GalleryAdder for Phase 1b (URL import via search). Favorites batch-add,
 * chapter sync and the E-Hentai paths are deliberately omitted; they belong to their later phases.
 */
class GalleryAdder(
    private val updateManga: UpdateManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {

    fun pickSource(url: String): List<UrlImportableSource> {
        val uri = url.toUri()
        return sourceManager.getOnlineSources()
            .mapNotNull { it.getMainSource<UrlImportableSource>() }
            .filter { runCatching { it.matchesUri(uri) }.getOrDefault(false) }
    }

    suspend fun addGallery(
        context: Context,
        url: String,
        fav: Boolean = false,
        forceSource: UrlImportableSource? = null,
    ): GalleryAddEvent {
        return try {
            val uri = url.toUri()
            val source = forceSource?.takeIf { runCatching { it.matchesUri(uri) }.getOrDefault(false) }
                ?: pickSource(url).firstOrNull()
                ?: return GalleryAddEvent.Fail.UnknownSource(url)

            val realMangaUrl = runCatching { source.mapUrlToMangaUrl(uri) }.getOrNull()
                ?: return GalleryAddEvent.Fail.UnknownType(url)
            val cleanedMangaUrl = runCatching { source.cleanMangaUrl(realMangaUrl) }.getOrNull()
                ?: return GalleryAddEvent.Fail.UnknownType(url)

            val httpSource = source as HttpSource

            // Get-or-create the local manga first so it has an id, then fetch details from the
            // source. Because the manga is now persisted, the enhanced source also captures and
            // stores its gallery metadata during this fetch.
            var manga = networkToLocalManga(
                Manga.create().copy(source = httpSource.id, url = cleanedMangaUrl),
            )
            val update = httpSource.getMangaUpdate(
                manga.toSManga(),
                emptyList(),
                fetchDetails = true,
                fetchChapters = false,
            )
            manga = manga.copyFrom(update.manga)
            updateManga.await(manga.toMangaUpdate())

            if (fav) {
                updateManga.awaitUpdateFavorite(manga.id, true)
                manga = manga.copy(favorite = true)
            }

            GalleryAddEvent.Success(url, manga)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not add gallery: $url" }
            GalleryAddEvent.Fail.Error(
                url,
                ((e.message ?: "Unknown error") + " (Gallery: $url)").trim(),
            )
        }
    }
}

sealed class GalleryAddEvent {
    abstract val galleryUrl: String

    class Success(
        override val galleryUrl: String,
        val manga: Manga,
    ) : GalleryAddEvent()

    sealed class Fail : GalleryAddEvent() {
        class UnknownType(override val galleryUrl: String) : Fail()
        open class Error(override val galleryUrl: String, val message: String) : Fail()
        class UnknownSource(override val galleryUrl: String) : Fail()
    }
}
