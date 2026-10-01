package reikai.data.novel

import mihon.domain.extension.model.ContentWarning
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource

/** Page 1 comes from [parseNovel]; the rest from [parsePage]. More than one page makes it a paged source. */
internal class PagedSource(
    private val firstPage: List<ChapterItem>,
    private val otherPages: Map<String, List<ChapterItem>> = emptyMap(),
    private val title: String = "Novel",
    private val summary: String? = null,
    private val genres: String? = null,
    private val cover: String? = null,
) : NovelSource {
    override val id = "src"
    override val name = "Source"
    override val version = "1.0.0"
    override val site = "https://src.example"
    override val lang = "en"
    override val iconUrl: String? = null
    override val format = NovelExtensionFormat.JS
    override val extensionName = "Source"
    override val contentWarning = ContentWarning.SAFE

    override suspend fun parseNovel(novelPath: String) =
        SourceNovel(
            path = novelPath,
            name = title,
            summary = summary,
            genres = genres,
            cover = cover,
            chapters = firstPage,
            totalPages = otherPages.size + 1,
        )

    override suspend fun parsePage(novelPath: String, page: String) =
        SourceNovel(path = novelPath, chapters = otherPages[page])

    override suspend fun parseChapter(chapterPath: String): String = unused()

    override suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): NovelItemsPage =
        unused()

    override suspend fun search(query: String, page: Int, filters: NovelFilterState?): NovelItemsPage = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("Not part of a refresh")
}
