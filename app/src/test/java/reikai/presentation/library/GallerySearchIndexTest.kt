package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** The gallery tags and alt-titles the library's tag search reads, when it reads them, and what it finds. */
class GallerySearchIndexTest {

    private val tags = listOf(
        tag(1L, "female", "yandere"),
        tag(2L, "female", "glasses"),
        tag(1L, "female", "blackmail"),
        tag(1L, "artist", "zorusoru"),
    )
    private val titles = listOf(SearchTitle(null, 2L, "Alt Title", 0))
    private val galleries = listOf(row(1L, gallery = true, artist = "zorusoru"), row(2L, gallery = true))
    private val library = galleries + row(3L, gallery = false)

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
            mapOf(1L to listOf(tags[0], tags[2], tags[3]), 2L to listOf(tags[1]))
    }

    @Test
    fun `a search groups the alt-titles by manga id`() = runTest {
        gallerySearchIndexFor("alt", galleries, { tags }, { titles }).titles shouldBe mapOf(2L to titles)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("searches")
    fun `a library search finds the rows the query names`(query: String, found: List<Long>) = runTest {
        search(query) shouldBe found
    }

    private suspend fun search(query: String): List<Long> {
        val index = gallerySearchIndexFor(query, library, { tags }, { titles })
        val fields = libraryItemQueryFields(sourceKey = { "1" }, galleryIndex = index)
        return library.filter { libraryQueryMatches(QueryNode.from(query), it, fields) }.map { it.id }
    }

    private fun tag(mangaId: Long, namespace: String, name: String) = SearchTag(null, mangaId, namespace, name, 0)

    private fun row(id: Long, gallery: Boolean, artist: String? = null) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = id, title = "Gallery $id", artist = artist),
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

    companion object {
        @JvmStatic
        fun searches() = listOf(
            Arguments.of("female:glasses", listOf(2L)),
            Arguments.of("artist:zoru*", listOf(1L)),
            Arguments.of("artist:al*", emptyList<Long>()),
            Arguments.of("alt", listOf(2L)),
            Arguments.of("female:glasses || female:yandere", listOf(1L, 2L)),
            Arguments.of("-female:yandere", listOf(2L, 3L)),
            Arguments.of("zoru -female:blackmail", emptyList<Long>()),
            Arguments.of("gallery -female:glasses", listOf(1L, 3L)),
            Arguments.of("female:yandere -artist:zorusoru", emptyList<Long>()),
            Arguments.of("\"-female:yandere\"", emptyList<Long>()),
            Arguments.of("artist:\"\"", listOf(2L, 3L)),
            Arguments.of("$", emptyList<Long>()),
            Arguments.of("gal*3", emptyList<Long>()),
        )
    }
}
