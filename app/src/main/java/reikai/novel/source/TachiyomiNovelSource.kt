package reikai.novel.source

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
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
import java.time.Instant

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
) : NovelSource {

    override val id: String = TACHIYOMI_NOVEL_SOURCE_PREFIX + source.id
    override val name: String = source.name
    override val version: String = extension.versionName
    override val site: String = (source as? HttpSource)?.baseUrl.orEmpty()
    override val lang: String = source.lang
    override val iconUrl: String = extensionIconUrl(extension.pkgName)
    override val format = NovelExtensionFormat.APK
    override val filters: NovelFilters? = source.getFilterList().takeIf { it.isNotEmpty() }
        ?.let { NovelFilters.FilterListSchema { source.getFilterList() } }
    override val settings: NovelSettings? = (source as? ConfigurableSource)?.let(NovelSettings::PreferenceScreen)
    override val supportsLatest: Boolean = source.supportsLatest

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
        val text = source.getPageList(chapter).map { source.fetchPageText(it) }.joinToString("\n")
        NovelTextSanitizer.stripInvalidChars(text)
    }

    override suspend fun resolveUrl(path: String, isNovel: Boolean): String? {
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
        // An ISO instant, the one form the novel date parser keeps to the millisecond; 0 means unknown.
        releaseTime = date_upload.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).toString() },
        chapterNumber = chapter_number.takeIf { it >= 0f }?.toDouble(),
    )
}
