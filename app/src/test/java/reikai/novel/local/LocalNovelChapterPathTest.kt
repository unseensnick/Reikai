package reikai.novel.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.novel.local.epub.EpubChapterKey

/** A chapter's stored address reads back as the same place in the same book. */
class LocalNovelChapterPathTest {

    @ParameterizedTest
    @MethodSource("addresses")
    fun `a chapter path reads back as where it starts`(series: String, volume: String, key: EpubChapterKey) {
        LocalNovelSource.parseChapterPath(LocalNovelSource.chapterPath(series, volume, key)) shouldBe
            Triple(series, volume, key)
    }

    companion object {
        @JvmStatic
        fun addresses() = listOf(
            Arguments.of("Saga: One", "01 - Book.epub", EpubChapterKey("OEBPS/a.xhtml")),
            Arguments.of("Saga", "Book.epub", EpubChapterKey("OEBPS/a b+c.xhtml", fragment = "id::with:colons")),
            Arguments.of("Saga", "Book.epub", EpubChapterKey("a.xhtml", heading = 2)),
            Arguments.of("A::B", "Book.epub", EpubChapterKey("a.xhtml")),
            Arguments.of("Saga", "x::y.epub", EpubChapterKey("a::b.xhtml")),
        )
    }
}
