package reikai.presentation.reader.web

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import reikai.data.coil.NovelImage
import reikai.novel.content.NovelImageSources

/** WebView mode's pictures reach the app's own fetch, and nothing else the page loads does. */
class NovelWebImagesTest {

    private val base = "https://site.example/novel/chapter-1"
    private val images = NovelWebImages()

    private fun img(html: String, baseUrl: String? = base) =
        Jsoup.parseBodyFragment(images.rewrite(html, baseUrl, "p")).selectFirst("img")!!

    @Test
    fun `a relative picture comes back as its absolute address and source`() {
        images.imageFor(img("""<img src="/a.jpg">""").attr("src")) shouldBe
            NovelImage("https://site.example/a.jpg", "p")
    }

    @Test
    fun `each srcset candidate keeps its descriptor, commas in its address included`() {
        val srcset = img("""<img srcset="https://cdn.example/w_100,h_50/a.jpg 100w, /b.jpg 2x">""").attr("srcset")

        NovelImageSources.parseSrcset(srcset).map { (url, descriptor) ->
            images.imageFor(url) to descriptor
        } shouldBe
            listOf(
                NovelImage("https://cdn.example/w_100,h_50/a.jpg", "p") to "100w",
                NovelImage("https://site.example/b.jpg", "p") to "2x",
            )
    }

    @Test
    fun `a protocol-relative picture with no base is https`() {
        images.imageFor(img("""<img src="//cdn.example/a.jpg">""", baseUrl = null).attr("src")) shouldBe
            NovelImage("https://cdn.example/a.jpg", "p")
    }

    @Test
    fun `an inline picture is left alone`() {
        img("""<img src="data:image/png;base64,AQID">""").attr("src") shouldBe "data:image/png;base64,AQID"
    }

    @Test
    fun `a routed address the rewrite never produced is refused`() {
        images.imageFor("https://appassets.androidplatform.net/rk-image?u=https%3A%2F%2Fevil.example%2Fx&s=p") shouldBe
            null
    }

    @Test
    fun `an address on another host is not a picture of ours`() {
        img("""<img src="/a.jpg">""")
        images.imageFor("https://site.example/rk-image?u=https%3A%2F%2Fsite.example%2Fa.jpg&s=p") shouldBe null
    }

    @Test
    fun `another address on the reserved host is not a picture of ours`() {
        img("""<img src="/a.jpg">""")
        images.imageFor(
            "https://appassets.androidplatform.net/assets/a.jpg?u=https%3A%2F%2Fsite.example%2Fa.jpg&s=p",
        ) shouldBe null
    }
}
