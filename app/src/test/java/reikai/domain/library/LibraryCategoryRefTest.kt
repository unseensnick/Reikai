package reikai.domain.library

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

class LibraryCategoryRefTest {

    private fun category(id: Long, name: String = "Reading") =
        Category(id = id, name = name, order = 0L, flags = 0L)

    @Test
    fun `the same raw id in each content type is a different category`() {
        val manga = LibraryCategoryRef(ContentType.MANGA, category(3))
        val novel = LibraryCategoryRef(ContentType.NOVELS, category(3))
        manga shouldNotBe novel
        manga.id shouldBe novel.id
    }

    @Test
    fun `ALL cannot qualify a category`() {
        shouldThrow<IllegalArgumentException> {
            LibraryCategoryRef(ContentType.ALL, category(3))
        }
    }
}
