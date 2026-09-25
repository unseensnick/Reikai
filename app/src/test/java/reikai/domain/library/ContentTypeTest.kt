package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ContentTypeTest {

    @ParameterizedTest(name = "{0} chip over {1}: {2}")
    @CsvSource(
        "ALL, MANGA, true",
        "ALL, NOVELS, true",
        "ALL, ALL, true",
        "MANGA, MANGA, true",
        "MANGA, NOVELS, false",
        "MANGA, ALL, false",
        "NOVELS, NOVELS, true",
        "NOVELS, MANGA, false",
        "NOVELS, ALL, false",
    )
    fun `a chip includes its own type, and All includes every type`(
        chip: ContentType,
        type: ContentType,
        included: Boolean,
    ) {
        chip.includes(type) shouldBe included
    }
}
