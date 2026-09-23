package reikai.novel.source.ireader

import eu.kanade.tachiyomi.extension.model.Extension
import ireader.core.source.CatalogSource
import ireader.core.source.HttpSource
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Command
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
import reikai.novel.source.NovelPageFetch
import reikai.novel.source.NovelPageKind
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
        novel.toSourceNovel(novelPath, chapters.map { it.toChapterItem() })
    }

    override suspend fun parseChapter(chapterPath: String): String = withIOContext {
        chapterHtml(chapterPath, emptyList())
    }

    // A source takes a page the user loaded through the Fetch commands it declares, the way through
    // for a site that blocks its requests; the novel keeps its own path rather than the page's address.
    override val pageFetch: NovelPageFetch? = pageKinds().takeIf { it.isNotEmpty() }?.let { kinds ->
        object : NovelPageFetch {
            override val kinds = kinds

            override suspend fun details(novelPath: String, url: String, html: String) = withIOContext {
                source.getMangaDetails(MangaInfo(key = novelPath, title = ""), listOf(Command.Detail.Fetch(url, html)))
                    .toSourceNovel(novelPath, chapters = null)
            }

            override suspend fun chapters(novelPath: String, url: String, html: String) = withIOContext {
                source.getChapterList(MangaInfo(key = novelPath, title = ""), listOf(Command.Chapter.Fetch(url, html)))
                    .map { it.toChapterItem() }
            }

            override suspend fun chapterText(chapterPath: String, url: String, html: String) = withIOContext {
                chapterHtml(chapterPath, listOf(Command.Content.Fetch(url, html)))
            }
        }
    }

    // A source's own code answers this, so one that throws offers no page fetch rather than failing the load.
    private fun pageKinds(): Set<NovelPageKind> = runCatching { source.getCommands() }.getOrDefault(emptyList())
        .mapNotNullTo(HashSet()) {
            when (it) {
                is Command.Detail.Fetch -> NovelPageKind.DETAILS
                is Command.Chapter.Fetch -> NovelPageKind.CHAPTERS
                is Command.Content.Fetch -> NovelPageKind.CHAPTER_TEXT
                else -> null
            }
        }

    // Strip control characters only, as the other formats do: the text is HTML the reader renders.
    private suspend fun chapterHtml(chapterPath: String, commands: List<Command<*>>): String {
        val pages = source.getPageList(ChapterInfo(key = chapterPath, name = ""), commands)
        val html = pages.toChapterHtml { page ->
            (source as? HttpSource)?.let { runCatching { it.getPage(page) }.getOrNull() }
        }
        return NovelTextSanitizer.stripInvalidChars(html)
    }

    private fun MangaInfo.toSourceNovel(path: String, chapters: List<ChapterItem>?) = SourceNovel(
        path = path,
        name = title.ifBlank { null },
        cover = cover.ifBlank { null },
        genres = genres.joinToString(", ").ifBlank { null },
        summary = description.ifBlank { null },
        author = author.ifBlank { null },
        artist = artist.ifBlank { null },
        // IReader numbers its statuses as tachiyomi does.
        status = NovelStatusCode.toSourceString(status.toInt()),
        chapters = chapters,
    )

    // IReader's own rule: a key is used as it is when absolute, and joined to the site otherwise.
    override fun webUrl(path: String, isNovel: Boolean): String = when {
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
