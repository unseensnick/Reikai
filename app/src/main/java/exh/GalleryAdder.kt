package exh

import android.content.Context
import androidx.core.net.toUri
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.copyFrom
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import eu.kanade.tachiyomi.source.online.all.EHentai
import exh.source.getMainSource
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Resolves a gallery URL into a local manga (and its chapters) by routing it to the matching
 * enhanced source. Drives both the share/open intercept and the bulk batch-add surfaces.
 *
 * Re-typed from Komikku's GalleryAdder onto Reikai's combined getMangaUpdate API: Komikku's split
 * getMangaDetails + getChapterList collapse into one getMangaUpdate call, and the EHentai throttle
 * is internal to that call (no throttleFunc seam to thread through).
 */
class GalleryAdder(
    private val updateManga: UpdateManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
) {

    // Snapshot the visibility filters once: only sources in an enabled language and not hidden can
    // claim a pasted URL, matching what the user sees in Browse.
    private val enabledLangs: Set<String> = sourcePreferences.enabledLanguages.get()
    private val disabledSources: Set<String> = sourcePreferences.disabledSources.get()

    private fun UrlImportableSource.isUsable(): Boolean =
        lang in enabledLangs && id.toString() !in disabledSources

    fun pickSource(url: String): List<UrlImportableSource> {
        val uri = url.toUri()
        return sourceManager.getOnlineSources()
            .mapNotNull { it.getMainSource<UrlImportableSource>() }
            .filter { it.isUsable() && runCatching { it.matchesUri(uri) }.getOrDefault(false) }
    }

    suspend fun addGallery(
        context: Context,
        url: String,
        fav: Boolean = false,
        forceSource: UrlImportableSource? = null,
        retry: Int = 1,
    ): GalleryAddEvent {
        logcat { "Importing gallery: $url (fav=$fav)" }
        return try {
            val uri = url.toUri()

            val source = if (forceSource != null) {
                if (runCatching { forceSource.matchesUri(uri) }.getOrDefault(false)) {
                    forceSource
                } else {
                    return GalleryAddEvent.Fail.UnknownSource(url, context)
                }
            } else {
                pickSource(url).firstOrNull() ?: return GalleryAddEvent.Fail.UnknownSource(url, context)
            }

            // A link may point at a single chapter rather than the gallery root: resolve the parent
            // gallery URL from it, and remember the cleaned chapter URL to open the reader there.
            val realChapterUrl = runCatching { source.mapUrlToChapterUrl(uri) }.getOrNull()
            val cleanedChapterUrl = realChapterUrl?.let { runCatching { source.cleanChapterUrl(it) }.getOrNull() }
            val chapterMangaUrl = realChapterUrl?.let { source.mapChapterUrlToMangaUrl(it.toUri()) }

            val realMangaUrl = (chapterMangaUrl ?: runCatching { source.mapUrlToMangaUrl(uri) }.getOrNull())
                ?: return GalleryAddEvent.Fail.UnknownType(url, context)
            val cleanedMangaUrl = runCatching { source.cleanMangaUrl(realMangaUrl) }.getOrNull()
                ?: return GalleryAddEvent.Fail.UnknownType(url, context)

            val httpSource = source as HttpSource

            // Get-or-create the local manga first so it has an id, then fetch details + chapters in
            // one call. Because the manga is now persisted, the enhanced source also captures and
            // stores its gallery metadata during this fetch.
            var manga = networkToLocalManga(
                Manga.create().copy(source = httpSource.id, url = cleanedMangaUrl),
            )
            val update = retry(retry) {
                httpSource.getMangaUpdate(
                    manga.toSManga(),
                    emptyList(),
                    fetchDetails = true,
                    fetchChapters = true,
                )
            }
            manga = manga.copyFrom(update.manga)
            updateManga.await(manga.toMangaUpdate())

            if (fav) {
                updateManga.awaitUpdateFavorite(manga.id, true)
                manga = manga.copy(favorite = true)
            }

            if (update.chapters.isNotEmpty()) {
                syncChaptersWithSource.await(update.chapters, manga, httpSource)
            }

            if (cleanedChapterUrl != null) {
                val chapter = getChapter.await(cleanedChapterUrl, manga.id)
                if (chapter != null) {
                    GalleryAddEvent.Success(url, manga, context, chapter)
                } else {
                    GalleryAddEvent.Fail.Error(
                        url,
                        context.stringResource(MR.strings.gallery_adder_could_not_identify_chapter, url),
                    )
                }
            } else {
                GalleryAddEvent.Success(url, manga, context)
            }
        } catch (e: EHentai.GalleryNotFoundException) {
            logcat(LogPriority.WARN, e) { "Gallery not found: $url" }
            GalleryAddEvent.Fail.NotFound(url, context)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not add gallery: $url" }
            GalleryAddEvent.Fail.Error(
                url,
                ((e.message ?: "Unknown error") + " (Gallery: $url)").trim(),
            )
        }
    }

    // Retries the fetch the given number of times, but surfaces a missing gallery immediately so it
    // can be reported as NotFound instead of burning retries on a 404.
    private inline fun <T : Any> retry(retryCount: Int, block: () -> T): T {
        var lastError: Exception? = null
        for (i in 1..retryCount) {
            try {
                return block()
            } catch (e: EHentai.GalleryNotFoundException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Retry count must be at least 1")
    }
}

sealed class GalleryAddEvent {
    abstract val logMessage: String
    abstract val galleryUrl: String
    open val galleryTitle: String? = null

    class Success(
        override val galleryUrl: String,
        val manga: Manga,
        val context: Context,
        val chapter: Chapter? = null,
    ) : GalleryAddEvent() {
        override val galleryTitle = manga.title
        override val logMessage = context.stringResource(MR.strings.batch_add_success_log_message, galleryTitle)
    }

    sealed class Fail : GalleryAddEvent() {
        class UnknownType(override val galleryUrl: String, val context: Context) : Fail() {
            override val logMessage = context.stringResource(MR.strings.batch_add_unknown_type_log_message, galleryUrl)
        }

        open class Error(
            override val galleryUrl: String,
            override val logMessage: String,
        ) : Fail()

        class NotFound(galleryUrl: String, context: Context) :
            Error(galleryUrl, context.stringResource(MR.strings.batch_add_not_exist_log_message, galleryUrl))

        class UnknownSource(override val galleryUrl: String, val context: Context) : Fail() {
            override val logMessage =
                context.stringResource(MR.strings.batch_add_unknown_source_log_message, galleryUrl)
        }
    }
}
