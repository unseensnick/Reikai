package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.jupiter.api.Test

/**
 * The title split behind the related-mangas search fallback. Every keyword it returns costs one
 * search request against the source, so the filtering and the cap are what stop a details open
 * from flooding a source that paces its requests.
 */
class CatalogueSourceKeywordTest {

    private fun split(title: String): List<String> = with(StubSource) {
        title.stripKeywordForRelatedMangas()
    }

    @Test
    fun `keeps only the distinctive words of a title`() {
        split("The Eminence in Shadow") shouldContainExactly listOf("eminence", "shadow")
    }

    @Test
    fun `drops digit-only tokens`() {
        split("Shadow 2") shouldContainExactly listOf("shadow")
    }

    @Test
    fun `drops repeated words`() {
        split("Shadow shadow") shouldContainExactly listOf("shadow")
    }

    @Test
    fun `splits on special characters`() {
        split("Shadow:Eminence") shouldContainExactly listOf("eminence", "shadow")
    }

    @Test
    fun `offers the longest word first, since only the first few are searched`() {
        split("Shadow Eminence") shouldContainExactly listOf("eminence", "shadow")
    }

    @Test
    fun `caps how many keywords one title can produce`() {
        split("Chronicles Adventure Wandering Traveller Champion") shouldHaveSize 3
    }
}

private object StubSource : CatalogueSource {
    override val id: Long = 1L
    override val name: String = "Stub"
    override val lang: String = "en"
    override val supportsLatest: Boolean = false

    override suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        throw UnsupportedOperationException()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = throw UnsupportedOperationException()

    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
}
