package eu.kanade.tachiyomi.source.online.english

import eu.kanade.tachiyomi.source.online.HttpSource
import exh.metadata.metadata.HentaiNexusSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.util.linkUri
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** A HentaiNexus gallery link imports as its gallery, and its page parses into namespaced metadata. */
class HentaiNexusTest {

    private val source = HentaiNexus(mockk<HttpSource>(relaxed = true), mockk(relaxed = true))

    private suspend fun parsed() = HentaiNexusSearchMetadata().also {
        source.parseIntoMetadata(it, Jsoup.parse(GALLERY, "https://hentainexus.com/view/$ID"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("links")
    fun `a link maps to its gallery`(url: String, expected: String?) = runTest {
        source.mapUrlToMangaUrl(linkUri(url)) shouldBe expected
    }

    @Test
    fun `the gallery id comes from the page address`() = runTest {
        parsed().hnId shouldBe ID
    }

    @Test
    fun `a tag drops the count the site shows beside it`() = runTest {
        parsed().tags shouldContain RaisedTag("tags", "big breasts", 0)
    }

    @Test
    fun `a details row is stored under its namespace`() = runTest {
        parsed().tags shouldContain RaisedTag("circle", "Some Circle", 0)
    }

    @Test
    fun `the favorite count reads past its thousands separator`() = runTest {
        parsed().favorites shouldBe 1234
    }

    @Test
    fun `the published day is stored as its UTC midnight`() = runTest {
        parsed().publishedDate shouldBe 1709424000000L
    }

    @Test
    fun `the reader thumbnails become the page previews`() = runTest {
        parsed().pagePreviewUrls shouldBe listOf("https://images.example/1.jpg", "https://images.example/2.jpg")
    }

    @Test
    fun `a tag chip searches the site's singular key, quoting several words`() {
        source.tagSearchQuery("tags", "big breasts") shouldBe "tag:\"big breasts\""
    }

    companion object {
        private const val ID = 123

        private val GALLERY = """
            <html><body>
            <h1 class="title">Gallery Title</h1>
            <figure class="image"><img src="https://images.example/cover.jpg"></figure>
            <table class="view-page-details">
              <tr><td class="viewcolumn">Artist</td><td><a href="/?q=artist:x">Some Artist</a></td></tr>
              <tr><td class="viewcolumn">Circle</td><td><a href="/?q=circle:x">Some Circle</a></td></tr>
              <tr><td class="viewcolumn">Published</td><td>3 March 2024</td></tr>
              <tr><td class="viewcolumn">Pages</td><td>24</td></tr>
              <tr><td class="viewcolumn">Favorites</td><td>1,234</td></tr>
              <tr><td class="viewcolumn">Tags</td><td><span class="tag"><a href="/?q=tag:x">big breasts (1,024)</a></span></td></tr>
            </table>
            <a href="/read/$ID/001"><div class="card-image"><img src="https://images.example/1.jpg"></div></a>
            <a href="/read/$ID/002"><div class="card-image"><img src="https://images.example/2.jpg"></div></a>
            </body></html>
        """.trimIndent()

        @JvmStatic
        fun links() = listOf(
            Arguments.of("https://hentainexus.com/view/$ID", "/view/$ID"),
            Arguments.of("https://hentainexus.com/read/$ID/005", "/view/$ID"),
            Arguments.of("https://hentainexus.com/page/2", null),
            Arguments.of("https://hentainexus.com/view/latest", null),
        )
    }
}
