package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NovelTextScaleTest {

    @ParameterizedTest(name = "Html.fromHtml's {0} becomes {1}")
    @CsvSource(
        "1.5, 2.0",
        "1.4, 1.5",
        "1.3, 1.17",
        "1.2, 1.0",
        "1.1, 0.83",
        "1.0, 0.67",
        "1.25, 1.0",
        "0.8, 0.83",
    )
    fun `each size the framework gives an element is set at the page's size for it`(given: Float, expected: Float) {
        NovelTextScale.forFromHtmlSize(given) shouldBe expected
    }

    @Test
    fun `a size the framework never gives is left alone`() {
        NovelTextScale.forFromHtmlSize(NovelTextScale.SCRIPT) shouldBe null
    }
}
