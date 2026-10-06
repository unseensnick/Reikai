package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.SearchEngine
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** The gallery tags and alt-titles the library's tag search reads, and when it reads them. */
class GallerySearchIndexTest {

    private val tags = listOf(tag(1L, "alp"), tag(2L, "beta"), tag(1L, "gamma"))
    private val titles = listOf(SearchTitle(null, 2L, "Alt Title", 0))
    private val galleries = listOf(row(1L, gallery = true), row(2L, gallery = true))

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "   "])
    fun `without a search neither table is read`(query: String?) = runTest {
        gallerySearchIndexFor(query, galleries, { error("tags read") }, { error("titles read") }) shouldBe
            GallerySearchIndex()
    }

    @Test
    fun `a search over a library with no gallery reads neither table`() = runTest {
        gallerySearchIndexFor("beta", listOf(row(1L, gallery = false)), { error("tags read") }, {
            error("titles read")
        }) shouldBe GallerySearchIndex()
    }

    @Test
    fun `a search groups the tags by manga id`() = runTest {
        gallerySearchIndexFor("female:", galleries, { tags }, { titles }).tags shouldBe
            mapOf(1L to listOf(tags[0], tags[2]), 2L to listOf(tags[1]))
    }

    @Test
    fun `a search groups the alt-titles by manga id`() = runTest {
        gallerySearchIndexFor("alt", galleries, { tags }, { titles }).titles shouldBe mapOf(2L to titles)
    }

    @Test
    fun `a gallery row matches a namespace tag indexed for its own id`() = runTest {
        val index = gallerySearchIndexFor("female:beta", galleries, { tags }, { titles })
        galleries[1].matchesMetadataQuery(SearchEngine().parseQuery("female:beta"), index) shouldBe true
    }

    @Test
    fun `a gallery row does not match a tag indexed for another id`() = runTest {
        val index = gallerySearchIndexFor("female:beta", galleries, { tags }, { titles })
        galleries[0].matchesMetadataQuery(SearchEngine().parseQuery("female:beta"), index) shouldBe false
    }

    @Test
    fun `a gallery row matches a word from its alt-title`() = runTest {
        val index = gallerySearchIndexFor("alt", galleries, { tags }, { titles })
        galleries[1].matchesMetadataQuery(SearchEngine().parseQuery("alt"), index) shouldBe true
    }

    private fun tag(mangaId: Long, name: String) = SearchTag(null, mangaId, "female", name, 0)

    private fun row(id: Long, gallery: Boolean) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = id, title = "Gallery $id"),
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        ),
        downloadCount = 0,
        unreadCount = 0,
        isLocal = false,
        badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
        metadataSourceName = "E-Hentai".takeIf { gallery },
    )
}
