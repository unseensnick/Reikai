package reikai.novel.source.ireader

import eu.kanade.tachiyomi.extension.model.Extension
import ireader.core.source.CatalogSource
import ireader.core.source.HttpSource
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Listing
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangasPageInfo
import reikai.data.coil.extensionIconUrl
import reikai.data.novel.NovelStatusCode
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelItem
import reikai.novel.host.NovelTextSanitizer
import reikai.novel.host.SourceNovel
import reikai.novel.source.AppNovelSource
import reikai.novel.source.IREADER_NOVEL_SOURCE_PREFIX
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelFilters
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelListing
import tachiyomi.core.common.util.lang.withIOContext
import java.time.Instant

/**
 * [reikai.novel.source.NovelSource] over the catalogue of an IReader extension. IReader lists chapters
 * oldest first, as novels keep them, and hands a chapter over as pages, which become HTML here. Its
 * listings map in order onto Popular and Latest; its search reads the query from a Title filter. Record:
 * docs/dev/plans/content-layer-sources-surface.md.
 */
class IReaderNovelSource(
    val source: CatalogSource,
    extension: Extension.Loaded,
) : AppNovelSource {

    override val appSource: Any get() = source

    override val id: String = IREADER_NOVEL_SOURCE_PREFIX + source.id
    override val name: String = source.name
    override val version: String = extension.versionName
    override val site: String = (source as? HttpSource)?.baseUrl.orEmpty()
    override val lang: String = source.lang
    override val iconUrl: String = extensionIconUrl(extension.pkgName)
    override val format = NovelExtensionFormat.IREADER
    override val filters: NovelFilters? = source.getFilters().takeIf { toMihonFilters(it).isNotEmpty() }
        ?.let { NovelFilters.FilterListSchema { toMihonFilters(source.getFilters()) } }
    override val supportsLatest: Boolean = source.getListings().size >= 2

    override suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): NovelItemsPage =
        withIOContext {
            val listings = source.getListings()
            val chosen: Listing? = when (listing) {
                NovelListing.Popular -> listings.firstOrNull()
                NovelListing.Latest -> listings.getOrNull(1)
            }
            source.getMangaList(chosen, page).toPage()
        }

    override suspend fun search(query: String, page: Int, filters: NovelFilterState?): NovelItemsPage =
        withIOContext {
            val picked = (filters as? NovelFilterState.Filters)?.list ?: toMihonFilters(source.getFilters())
            source.getMangaList(toIReaderFilters(picked, query), page).toPage()
        }

    override suspend fun parseNovel(novelPath: String): SourceNovel = withIOContext {
        val novel = source.getMangaDetails(MangaInfo(key = novelPath, title = ""), emptyList())
        val chapters = source.getChapterList(novel.copy(key = novelPath), emptyList())
        SourceNovel(
            path = novelPath,
            name = novel.title.ifBlank { null },
            cover = novel.cover.ifBlank { null },
            genres = novel.genres.joinToString(", ").ifBlank { null },
            summary = novel.description.ifBlank { null },
            author = novel.author.ifBlank { null },
            artist = novel.artist.ifBlank { null },
            // IReader numbers its statuses as tachiyomi does.
            status = NovelStatusCode.toSourceString(novel.status.toInt()),
            chapters = chapters.map { it.toChapterItem() },
        )
    }

    // Strip control characters only, as the other formats do: the text is HTML the reader renders.
    override suspend fun parseChapter(chapterPath: String): String = withIOContext {
        val pages = source.getPageList(ChapterInfo(key = chapterPath, name = ""), emptyList())
        val html = pages.toChapterHtml { page ->
            (source as? HttpSource)?.let { runCatching { it.getPage(page) }.getOrNull() }
        }
        NovelTextSanitizer.stripInvalidChars(html)
    }

    // IReader's own rule: a key is used as it is when absolute, and joined to the site otherwise.
    override fun webUrl(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("/") -> site + path
        else -> "$site/$path"
    }

    private fun MangasPageInfo.toPage() = NovelItemsPage(
        items = mangas.map { NovelItem(name = it.title, path = it.key, cover = it.cover.ifBlank { null }) },
        hasNextPage = hasNextPage,
    )

    private fun ChapterInfo.toChapterItem() = ChapterItem(
        name = name,
        path = key,
        // An ISO instant, the one form the novel date parser keeps to the millisecond; 0 means unknown.
        releaseTime = dateUpload.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).toString() },
        chapterNumber = number.takeIf { it >= 0f }?.toDouble(),
    )
}
