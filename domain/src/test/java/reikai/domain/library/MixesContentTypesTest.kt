package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The download queue and the Repos screen badge a row's type by this one rule. */
class MixesContentTypesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `only a list holding both types mixes them`(types: List<ContentType>, mixes: Boolean) {
        mixesContentTypes(types) shouldBe mixes
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of(emptyList<ContentType>(), false),
            Arguments.of(listOf(ContentType.MANGA, ContentType.MANGA), false),
            Arguments.of(listOf(ContentType.MANGA, ContentType.NOVELS), true),
        )
    }
}
