package reikai.novel.download

import android.util.Base64
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.novel.network.NovelImageClient

/** A downloaded chapter's copy must not send either reader back to the network for an image. */
class NovelChapterImageInlinerTest {

    /** The image host, answering every address with a few bytes of image. */
    private val fetched = mutableListOf<String>()
    private val referers = mutableListOf<String?>()
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            fetched += chain.request().url.toString()
            referers += chain.request().header("Referer")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "image/png")
                .body(byteArrayOf(1, 2, 3).toResponseBody())
                .build()
        }
        .build()

    @BeforeEach
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } returns "AQID"
    }

    @AfterEach
    fun tearDown() = unmockkStatic(Base64::class)

    private suspend fun inlined(html: String) =
        Jsoup.parse(inlineChapterImages(html, "https://site.example/chapter/1", images)).selectFirst("img")!!

    private val images get() = NovelImageClient(client, headersOf("Referer", "https://site.example/"))

    @Test
    fun `an image with a srcset is stored with no remote candidate left`() = runTest {
        val img = inlined("""<p><img src="/a.jpg" srcset="/a-800.jpg 800w, /a-1600.jpg 1600w"></p>""")

        (img.attr("src").startsWith("data:") to img.hasAttr("srcset")) shouldBe (true to false)
    }

    @Test
    fun `an image with only a srcset is stored from its widest candidate`() = runTest {
        inlined("""<p><img srcset="/a-800.jpg 800w, /a-1600.jpg 1600w"></p>""")

        fetched shouldBe listOf("https://site.example/a-1600.jpg")
    }

    @Test
    fun `a lazy-load placeholder in src is stored as the picture its srcset names`() = runTest {
        inlined("""<img src="data:image/gif;base64,R0lGOD" srcset="/ill-800.jpg 800w">""")

        fetched shouldBe listOf("https://site.example/ill-800.jpg")
    }

    @Test
    fun `an image already stored inline is left as it is`() = runTest {
        inlined("""<img src="data:image/png;base64,AQID">""")

        fetched shouldBe emptyList()
    }

    @Test
    fun `a stored image is fetched with its source's image headers`() = runTest {
        inlined("""<img src="/a.jpg">""")

        referers shouldBe listOf("https://site.example/")
    }

    @Test
    fun `a picture is stored as one self-contained image`() = runTest {
        val img = inlined("""<picture><source srcset="/a-800.jpg 800w"><img alt="a"></picture>""")

        (img.attr("src").startsWith("data:") to img.hasAttr("srcset")) shouldBe (true to false)
    }

    @Test
    fun `an image that cannot be fetched is left as it came`() = runTest {
        val failing = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Gone")
                    .body(ByteArray(0).toResponseBody()).build()
            }
            .build()
        val html = """<p><img src="/a.jpg" srcset="/a-800.jpg 800w"></p>"""

        val img = Jsoup.parse(
            inlineChapterImages(html, "https://site.example/chapter/1", NovelImageClient(failing, headersOf())),
        ).selectFirst("img")!!

        (img.attr("src") to img.attr("srcset")) shouldBe ("/a.jpg" to "/a-800.jpg 800w")
    }
}
