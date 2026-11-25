package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * A field set in Edit info is what library search reads for that field, through the one binding both
 * content types call, so an edited series is found by the new value and no longer by the source's.
 */
class LibraryCustomInfoSearchTest {

    @ParameterizedTest
    @EnumSource(Field::class)
    fun `an Edit info value finds the series`(field: Field) {
        matches(field, "${field.term}:edited") shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Field::class)
    fun `the source value no longer finds an edited series`(field: Field) {
        matches(field, "${field.term}:original") shouldBe false
    }

    enum class Field(val term: String, val overlay: LibraryQueryOverlay) {
        AUTHOR("author", LibraryQueryOverlay(author = "edited")),
        ARTIST("artist", LibraryQueryOverlay(artist = "edited")),
        DESCRIPTION("desc", LibraryQueryOverlay(description = "edited")),
    }

    private fun matches(field: Field, query: String) = libraryQueryMatches(
        QueryNode.from(query),
        ROW,
        libraryItemQueryFields(sourceKey = { "1" }, overlay = mapOf(ROW.id to field.overlay)),
    )

    private companion object {
        val ROW = LibraryItem(
            libraryManga = LibraryManga(
                manga = Manga.create().copy(
                    id = 1L,
                    author = "original",
                    artist = "original",
                    description = "original",
                ),
                categories = emptyList(),
                totalChapters = 0,
                readCount = 0,
                bookmarkCount = 0,
                latestUpload = null,
                chapterFetchedAt = null,
                lastRead = null,
            ),
            downloadCount = 0,
            unreadCount = 0,
            isLocal = false,
            badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
        )
    }
}
