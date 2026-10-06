package reikai.presentation.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.category.CategoryContentType
import tachiyomi.i18n.MR

class CategoryContentTypeLabelTest {

    @Test
    fun `a universal category is labelled as serving every library`() {
        categoryContentTypeLabel(CategoryContentType.UNIVERSAL) shouldBe MR.strings.category_content_type_all
    }

    @Test
    fun `a manga category is labelled manga only`() {
        categoryContentTypeLabel(CategoryContentType.MANGA) shouldBe MR.strings.category_content_type_manga
    }

    @Test
    fun `a novel category is labelled novels only`() {
        categoryContentTypeLabel(CategoryContentType.NOVEL) shouldBe MR.strings.category_content_type_novels
    }
}
