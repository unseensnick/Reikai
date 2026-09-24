package reikai.novel.source

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.RateLimited
import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import reikai.data.coil.extensionIconUrl
import reikai.data.novel.NovelStatusCode
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelItem
import reikai.novel.host.NovelTextSanitizer
import reikai.novel.host.SourceNovel
import tachiyomi.core.common.util.lang.withIOContext

/**
 * [NovelSource] over one catalogue of a novel extension app, a class the app loaded from the apk rather
 * than a plugin it runs. The apk speaks the manga contract, so everything crosses it here: a novel is an
 * `SManga`, a chapter an `SChapter`, and its text comes page by page through `fetchPageText`. Each
 * call leaves the caller's thread, since an older extension fetches on whichever thread asks, while a
 * plugin runs on its host's own thread and is safe to call from any.
 */
class TachiyomiNovelSource(
    val source: CatalogueSource,
    extension: Extension.Loaded,
) : AppNovelSource {

    override val appSource: Any get() = source

    override val id: String = TACHIYOMI_NOVEL_SOURCE_PREFIX + source.id
    override val name: String = source.name
    override val version: String = extension.versionName
    override val site: String = (source as? HttpSource)?.baseUrl.orEmpty()
    override val lang: String = source.lang
    override val iconUrl: String = extensionIconUrl(extension.pkgName)
    override val format = NovelExtensionFormat.APK
    override val extensionName: String = extension.name
    override val contentWarning = extension.contentWarning
    override val filters: NovelFilters? = source.getFilterList().takeIf { it.isNotEmpty() }
        ?.let { NovelFilters.FilterListSchema { source.getFilterList() } }
    override val settings: NovelSettings? = (source as? ConfigurableSource)?.let(NovelSettings::PreferenceScreen)
    override val supportsLatest: Boolean = source.supportsLatest
    override val tracker: SourceTracker? = source as? SourceTracker

    // The extension's own code answers this, so a failure reads as no minimum rather than a crash.
    override val minimumRequestDelayMs: Long = (source as? RateLimited)
        ?.let { runCatching { it.minimumDelayMillis }.getOrNull() }
        ?.coerceAtLeast(0L)
        ?: 0L

    // A listing takes no filters here, as a manga source's Popular and Latest take none.
    override suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): NovelItemsPage =
        withIOContext {
            when (listing) {
                NovelListing.Popular -> source.getPopularManga(page)
                NovelListing.Latest -> source.getLatestUpdates(page)
            }.toPage()
        }

    override suspend fun search(query: String, page: Int, filters: NovelFilterState?): NovelItemsPage =
        withIOContext {
            val filterList = (filters as? NovelFilterState.Filters)?.list ?: source.getFilterList()
            source.getSearchManga(page, query, filterList).toPage()
        }

    override suspend fun parseNovel(novelPath: String): SourceNovel = withIOContext {
        val update = source.getMangaUpdate(
            SManga.create().apply { url = novelPath },
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = true,
        )
        val manga = update.manga
        SourceNovel(
            path = novelPath,
            // Details that failed to parse leave the title unset, which reads as missing rather than throwing.
            name = runCatching { manga.title }.getOrNull(),
            cover = manga.thumbnail_url,
            genres = manga.genre,
            summary = manga.description,
            author = manga.author,
            artist = manga.artist,
            status = NovelStatusCode.toSourceString(manga.status),
            // The contract lists the newest chapter first; novels keep them in reading order.
            chapters = update.chapters.asReversed().map { it.toChapterItem() },
        )
    }

    // Strip control characters only, as the plugin adapter does: the text is HTML the reader renders.
    override suspend fun parseChapter(chapterPath: String): String = withIOContext {
        val chapter = SChapter.create().apply { url = chapterPath }
        val text = source.getPageList(chapter).map { source.fetchPageText(it.addressed()) }.joinToString("\n")
        NovelTextSanitizer.stripInvalidChars(text)
    }

    // NovelSourcery's ReadWN theme names the chapter only as the page's picture, then fetches the page's
    // own address, which is empty and lands on the site's home page.
    private fun Page.addressed(): Page =
        if (url.isBlank() && !imageUrl.isNullOrBlank()) Page(index, imageUrl!!, imageUrl) else this

    // An app's stored path need not be relative to its site (a bare series slug, say), so its own
    // rule answers; one that throws falls back to the site join rather than hiding the page.
    override suspend fun resolveUrl(path: String, isNovel: Boolean): String? =
        runCatching { appUrl(path, isNovel) }.getOrNull()

    private fun appUrl(path: String, isNovel: Boolean): String? {
        val http = source as? HttpSource ?: return null
        return if (isNovel) {
            http.getMangaUrl(SManga.create().apply { url = path })
        } else {
            http.getChapterUrl(SChapter.create().apply { url = path })
        }
    }

    private fun MangasPage.toPage() = NovelItemsPage(
        items = mangas.map { NovelItem(name = it.title, path = it.url, cover = it.thumbnail_url) },
        hasNextPage = hasNextPage,
    )

    private fun SChapter.toChapterItem() = ChapterItem(
        name = name,
        path = url,
        releaseTime = releaseTimeOf(date_upload),
        chapterNumber = chapterNumberOf(chapter_number),
    )
}
