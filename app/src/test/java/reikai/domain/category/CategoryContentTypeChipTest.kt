package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import tachiyomi.domain.category.model.Category

/**
 * Covers what an All / Manga / Novels chip narrows a category list to: the chip's own rows plus the
 * universal ones, which belong to both libraries and so survive every chip.
 */
class CategoryContentTypeChipTest {

    private fun category(id: Long, contentType: Long) = Category(
        id = id,
        name = "cat$id",
        order = id,
        flags = 0L,
        contentType = contentType,
    )

    private val universal = category(1, CategoryContentType.UNIVERSAL)
    private val manga = category(2, CategoryContentType.MANGA)
    private val novel = category(3, CategoryContentType.NOVEL)
    private val all = listOf(universal, manga, novel)

    @Test
    fun `the All chip narrows nothing`() {
        categoriesForContentType(all, ContentType.ALL) shouldBe all
    }

    @Test
    fun `the Manga chip keeps manga rows and the universal ones`() {
        categoriesForContentType(all, ContentType.MANGA) shouldBe listOf(universal, manga)
    }

    @Test
    fun `the Novels chip keeps novel rows and the universal ones`() {
        categoriesForContentType(all, ContentType.NOVELS) shouldBe listOf(universal, novel)
    }

    @Test
    fun `narrowing keeps the order it was given`() {
        categoriesForContentType(listOf(novel, universal, manga), ContentType.NOVELS) shouldBe
            listOf(novel, universal)
    }

    @Test
    fun `a category created under the Novels chip is a novel category`() {
        ContentType.NOVELS.toCategoryContentType() shouldBe CategoryContentType.NOVEL
    }

    @Test
    fun `a category created under the All chip serves both libraries`() {
        ContentType.ALL.toCategoryContentType() shouldBe CategoryContentType.UNIVERSAL
    }
}
