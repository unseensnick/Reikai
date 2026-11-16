package reikai.presentation.reader.web

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import reikai.data.coil.NovelImage
import reikai.novel.content.NovelImageSources
import reikai.novel.local.LocalNovelSource

/** WebView mode's pictures reach the app's own fetch, and nothing else the page loads does. */
class NovelWebImagesTest {

    private val base = "https://site.example/novel/chapter-1"
    private val images = NovelWebImages()

    private fun img(html: String, baseUrl: String? = base) =
        Jsoup.parseBodyFragment(images.rewrite(html, baseUrl, "p")).selectFirst("img")!!

    // reader.js marks a Retry's address so the app fetches the picture again rather than serve its cached bytes.
    @Test
    fun `a Retry's address still names its picture and reads as a retry`() {
        val retry = img("""<img src="/a.jpg">""").attr("src") + "&retry=1"

        (images.imageFor(retry) to images.isRetry(retry)) shouldBe
            (NovelImage("https://site.example/a.jpg", "p") to true)
    }

    @Test
    fun `a first load is no retry`() {
        images.isRetry(img("""<img src="/a.jpg">""").attr("src")) shouldBe false
    }

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
    fun `a local book's picture is served by the app, since the page cannot read files`() {
        val address = LocalNovelSource.pictureAddress("Saga", "Book.epub", "OEBPS/plate.png", 1)
        val src = Jsoup.parseBodyFragment(images.rewrite("""<img src="$address">""", null, LocalNovelSource.ID))
            .selectFirst("img")!!.attr("src")

        images.imageFor(src) shouldBe NovelImage(address, LocalNovelSource.ID)
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

    /** The source half of the key: a picture routed for one source is not served with another's headers. */
    @Test
    fun `a routed picture asked for under another source is refused`() {
        val src = img("""<img src="/a.jpg">""").attr("src")

        images.imageFor(src.replace("s=p", "s=q")) shouldBe null
    }

    // A lazy picture beyond the fold is never fetched, so the page's image wait would run out its cap.
    @Test
    fun `a lazy picture is loaded at once`() {
        img("""<img loading="lazy" src="/a.jpg">""").hasAttr("loading") shouldBe false
    }

    @Test
    fun `a picture element's source candidates are routed too`() {
        val srcset = Jsoup.parseBodyFragment(
            images.rewrite("""<picture><source srcset="/a.webp 1x"><img src="/b.jpg"></picture>""", base, "p"),
        ).selectFirst("source")!!.attr("srcset")

        images.imageFor(NovelImageSources.parseSrcset(srcset).single().first) shouldBe
            NovelImage("https://site.example/a.webp", "p")
    }

    @Test
    fun `the rewrite keeps a chapter's own whitespace`() {
        val html = "<div style=\"white-space:pre-wrap\">a\n\n  b<img src=\"/x.png\"></div>"

        Jsoup.parseBodyFragment(images.rewrite(html, base, "p")).selectFirst("div")!!
            .textNodes().first().wholeText shouldBe "a\n\n  b"
    }

    /** reader.js marks a Retry by these two tokens, so they must match the address the rewrite builds. */
    @Test
    fun `the page's route and retry key mark a rewritten picture as a retry`() {
        val src = img("""<img src="/a.jpg">""").attr("src")
        val retry = src.takeIf { it.startsWith(NovelWebImages.PAGE_ROUTE) } + "&${NovelWebImages.RETRY}=1"

        images.isRetry(retry) shouldBe true
    }
}
