package exh.util

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class LazyImageAttrTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "'<img data-src=\"/lazy.jpg\" src=\"/blank.gif\">', https://example.org/lazy.jpg",
        "'<img data-cfsrc=\"/cf.jpg\" src=\"/blank.gif\">', https://example.org/cf.jpg",
        "'<img src=\"/plain.jpg\">', https://example.org/plain.jpg",
        "'<img>', ",
    )
    fun `reads the address a lazy loader moved the image to`(html: String, expected: String?) {
        val img = Jsoup.parse(html, "https://example.org/").selectFirst("img")!!

        img.lazyImageUrl() shouldBe expected
    }
}
