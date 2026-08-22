package reikai.domain.recommendation.taste

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * MyAnimeList rates an entry `white`, `gray` or `black` and documents none of them, so the reading
 * this app commits to is pinned here rather than left implicit in a `when`.
 */
class MyAnimeListAdultMappingTest {

    @ParameterizedTest
    @CsvSource(
        "white, CLEAN",
        "gray, ADULT",
        "grey, ADULT",
        "black, ADULT",
        "WHITE, CLEAN",
    )
    fun `MAL's rating maps to a single adult verdict`(nsfw: String, expected: AdultContent) {
        malNsfwToAdultContent(nsfw) shouldBe expected
    }

    @Test
    fun `an entry MAL has not rated stays unknown so the tags can still answer`() {
        malNsfwToAdultContent(null) shouldBe AdultContent.UNKNOWN
    }
}
