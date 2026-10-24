package eu.kanade.tachiyomi.source.online.english

import android.net.Uri
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.metadata.metadata.NineHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI

/** A NineHentai gallery link imports as its gallery, and its page parses into namespaced metadata. */
class NineHentaiTest {

    private val source = NineHentai(
        mockk<HttpSource>(relaxed = true) { every { baseUrl } returns "https://9hentai.so" },
        mockk(relaxed = true),
    )

    private fun uri(url: String): Uri {
        val parsed = URI(url)
        return mockk {
            every { host } returns parsed.host
            every { pathSegments } returns parsed.path.split('/').filter { it.isNotEmpty() }
        }
    }

    private suspend fun parsed(book: NineHentai.Book? = null) = NineHentaiSearchMetadata().also {
        source.parseIntoMetadata(
            it,
            NineHentai.GalleryInput(Jsoup.parse(GALLERY, "https://9hentai.so/g/$ID/"), book),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("links")
    fun `a link maps to its gallery`(url: String, expected: String?) = runTest {
        source.mapUrlToMangaUrl(uri(url)) shouldBe expected
    }

    @Test
    fun `the gallery id comes from the page address`() = runTest {
        parsed().nhId shouldBe ID
    }

    @Test
    fun `the cover comes from the site's own image host`() = runTest {
        parsed().thumbnailUrl shouldBe "https://i.9hentai.so/images/$ID/cover.jpg"
    }

    @Test
    fun `the page count reads from the pages line`() = runTest {
        parsed().pageCount shouldBe 24
    }

    @Test
    fun `the site's Tag label is stored under the shared tags namespace`() = runTest {
        parsed().tags shouldContain RaisedTag("tags", "big breasts", 0)
    }

    @Test
    fun `another label is stored as its own namespace`() = runTest {
        parsed().tags shouldContain RaisedTag("artist", "someone", 0)
    }

    @Test
    fun `the counts come from the api`() = runTest {
        parsed(NineHentai.Book(id = ID, totalFavorite = 7)).favorites shouldBe 7
    }

    companion object {
        private const val ID = 456

        private val GALLERY = """
            <html><body>
            <div id="info">
              <h1>Gallery Title</h1>
              <h2>Alt Title</h2>
              <div>24 pages</div>
              <div>Uploaded <time>3 days ago</time></div>
              <section id="tags">
                <div class="tag-container field-name">Tag: <span class="tags"><a class="tag">big breasts</a></span></div>
                <div class="tag-container field-name">Artist: <span class="tags"><a class="tag">someone</a></span></div>
              </section>
            </div>
            </body></html>
        """.trimIndent()

        @JvmStatic
        fun links() = listOf(
            Arguments.of("https://9hentai.so/g/$ID/", "/g/$ID"),
            Arguments.of("https://www.9hentai.so/g/$ID", "/g/$ID"),
            Arguments.of("https://9hentai.so/t/$ID/", null),
            Arguments.of("https://9hentai.so/g/", null),
        )
    }
}
