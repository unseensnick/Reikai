package eu.kanade.presentation.manga.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HalfStarsTest {

    // The site's own star image for an exact average rounds to the nearest half (3.91 draws four
    // stars, 1.33 draws one and a half), so browse and details both follow it.
    @ParameterizedTest
    @CsvSource("4.46, 4.5", "4.24, 4.0", "4.75, 5.0", "3.91, 4.0", "1.33, 1.5", "0, 0")
    fun `an average rating draws the nearest half star`(average: Float, stars: Float) {
        average.toHalfStars() shouldBe stars
    }
}
