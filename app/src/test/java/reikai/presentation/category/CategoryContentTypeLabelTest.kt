package reikai.presentation.category

import dev.icerock.moko.resources.StringResource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.category.CategoryContentType
import tachiyomi.i18n.MR

class CategoryContentTypeLabelTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    fun `a category is labelled with the libraries it serves`(type: Long, label: StringResource) {
        categoryContentTypeLabel(type) shouldBe label
    }

    companion object {
        @JvmStatic
        fun labels() = listOf(
            Arguments.of(CategoryContentType.UNIVERSAL, MR.strings.category_content_type_all),
            Arguments.of(CategoryContentType.MANGA, MR.strings.category_content_type_manga),
            Arguments.of(CategoryContentType.NOVEL, MR.strings.category_content_type_novels),
        )
    }
}
