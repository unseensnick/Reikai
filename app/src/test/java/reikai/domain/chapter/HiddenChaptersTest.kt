package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class HiddenChaptersTest {

    @Test
    fun `a merged copy is keyed by the source of the manga that owns it`() {
        val copy = Chapter.create().copy(id = 20L, mangaId = 2L, url = "/c1")
        val owner = Manga.create().copy(id = 2L, source = 9L)

        copy.hiddenKey(owner) shouldBe "9|/c1"
    }
}
