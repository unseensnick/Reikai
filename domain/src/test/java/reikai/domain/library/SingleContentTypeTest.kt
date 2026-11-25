package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The library's Merge and the migration entry point both act only on a selection of one type. */
class SingleContentTypeTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `only a non-empty list of one type has a single type`(types: List<ContentType>, single: ContentType?) {
        singleContentType(types) shouldBe single
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of(emptyList<ContentType>(), null),
            Arguments.of(listOf(ContentType.MANGA, ContentType.MANGA), ContentType.MANGA),
            Arguments.of(listOf(ContentType.MANGA, ContentType.NOVELS), null),
        )
    }
}
