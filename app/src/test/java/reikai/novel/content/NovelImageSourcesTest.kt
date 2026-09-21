package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** A srcset is split as a browser splits it, so the text reader and a download pick the picture it would. */
class NovelImageSourcesTest {

    @Test
    fun `an address ending in a comma ends its candidate there`() {
        NovelImageSources.parseSrcset("a.jpg, b.jpg 2x") shouldBe listOf("a.jpg" to "", "b.jpg" to "2x")
    }

    @Test
    fun `the picked candidate keeps a comma inside its address`() {
        val img = Jsoup.parseBodyFragment(
            """<img srcset="https://cdn.example/w_400,h_1/a.jpg 400w, https://cdn.example/w_800,h_1/b.jpg 800w">""",
        ).selectFirst("img")!!

        NovelImageSources.srcsetCandidate(img, targetWidth = 500) shouldBe "https://cdn.example/w_800,h_1/b.jpg"
    }
}
