package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.domain.category.model.Category

/** A restore binds a category by name, and a typed row wins over a universal row with the same name. */
class CategoryByNameTest {

    private fun row(id: Long, contentType: Long) =
        Category(id = id, name = "Reading", order = id, flags = 0, contentType = contentType)

    @ParameterizedTest
    @ValueSource(longs = [CategoryContentType.MANGA, CategoryContentType.NOVEL])
    fun `a name shared with a universal row binds to the content type's own row`(contentType: Long) {
        listOf(row(1, CategoryContentType.UNIVERSAL), row(2, contentType), row(3, CategoryContentType.UNIVERSAL))
            .byNamePreferring(contentType)["Reading"]?.id shouldBe 2L
    }

    @ParameterizedTest
    @ValueSource(longs = [CategoryContentType.MANGA, CategoryContentType.NOVEL])
    fun `a name only a universal row has still binds to it`(contentType: Long) {
        listOf(row(1, CategoryContentType.UNIVERSAL)).byNamePreferring(contentType)["Reading"]?.id shouldBe 1L
    }
}
