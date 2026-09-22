package reikai.novel.source.ireader

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import ireader.core.source.CatalogSource
import ireader.core.source.HttpSource

/**
 * An IReader catalogue, carried in an installed extension's source list, which holds tachiyomi
 * sources. It answers only what that list is read for (the number, name and language); the novel
 * adapter reads [source] itself, and no manga path ever sees one, since novel apps have maps of their own.
 */
class IReaderSourceHolder(val source: CatalogSource) : Source {

    override val id: Long get() = source.id

    override val name: String get() = source.name

    override val lang: String get() = source.lang

    override val supportsLatest: Boolean get() = false

    /** The site, for what the extension list searches and what clearing its cookies clears. */
    val baseUrl: String? get() = (source as? HttpSource)?.baseUrl

    override suspend fun getPopularManga(page: Int): MangasPage = notManga()

    override suspend fun getLatestUpdates(page: Int): MangasPage = notManga()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = notManga()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = notManga()

    override suspend fun getPageList(chapter: SChapter): List<Page> = notManga()

    private fun notManga(): Nothing = throw UnsupportedOperationException("An IReader source is read as a novel source")
}
