package reikai.domain.recommendation.taste

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Kitsu's two adult signals. Kitsu flags 25 of its 243 categories NSFW (measured 2026-08-23), and
 * two of them are not sexual content by Reikai's definition, which is the whole reason this is a
 * mapping rather than a straight read of the flag.
 */
class KitsuAdultMappingTest {

    @Test
    fun `a sexual category makes the entry adult`() {
        kitsuAdultContent(listOf("Bondage"), sfw = true) shouldBe AdultContent.ADULT
    }

    @Test
    fun `an R18 rating makes the entry adult even with no flagged category`() {
        kitsuAdultContent(emptyList(), sfw = false) shouldBe AdultContent.ADULT
    }

    @Test
    fun `Yuri alone does not, since orientation is not explicitness`() {
        kitsuAdultContent(listOf("Yuri"), sfw = true) shouldBe AdultContent.UNKNOWN
    }

    @Test
    fun `Nudity alone does not, matching the keyword list's own ruling`() {
        kitsuAdultContent(listOf("Nudity"), sfw = true) shouldBe AdultContent.UNKNOWN
    }

    @Test
    fun `an excluded category beside a sexual one still reads adult`() {
        kitsuAdultContent(listOf("Yuri", "Sex Toys"), sfw = true) shouldBe AdultContent.ADULT
    }

    @Test
    fun `a clean entry is unknown rather than clean, so the tag fallback still speaks`() {
        kitsuAdultContent(emptyList(), sfw = true) shouldBe AdultContent.UNKNOWN
    }

    @Test
    fun `an unasked sfw field is not read as adult`() {
        kitsuAdultContent(emptyList(), sfw = null) shouldBe AdultContent.UNKNOWN
    }

    @Test
    fun `category matching ignores case, since Kitsu titles them in title case`() {
        kitsuAdultContent(listOf("YURI"), sfw = true) shouldBe AdultContent.UNKNOWN
    }
}
