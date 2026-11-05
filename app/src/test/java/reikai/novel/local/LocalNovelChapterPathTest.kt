package reikai.novel.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.novel.local.epub.EpubCut

/** A chapter's stored address reads back as the same place in the same book. */
class LocalNovelChapterPathTest {

    @ParameterizedTest
    @MethodSource("cuts")
    fun `a chapter path reads back as where it starts`(cut: EpubCut) {
        LocalNovelSource.parseChapterPath(LocalNovelSource.chapterPath("Saga: One", "01 - Book.epub", cut)) shouldBe
            Triple("Saga: One", "01 - Book.epub", cut)
    }

    companion object {
        @JvmStatic
        fun cuts() = listOf(EpubCut(0), EpubCut(3, fragment = "id::with:colons"), EpubCut(1, heading = 2))
    }
}
