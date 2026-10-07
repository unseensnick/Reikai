package exh.source

import android.content.SharedPreferences
import exh.metadata.metadata.NHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException

/** One nhentai v2 gallery reads the same way for the built-in source and the extension wrapper. */
class NHentaiApiTest {

    private val gallery = nhJson.decodeFromString<NhGallery>(
        """
        {
          "id": 177013,
          "media_id": "987560",
          "title": {"english": "English Title", "japanese": "Japanese Title", "pretty": "Pretty Title"},
          "thumbnail": {"path": "galleries/987560/thumb.jpg", "width": 350, "height": 500},
          "scanlator": "   ",
          "upload_date": 1476793729,
          "num_favorites": 42,
          "tags": [
            {"id": 1, "type": "category", "name": "doujinshi", "count": 9},
            {"id": 2, "type": "tag", "name": "full color"},
            {"id": 3, "type": "artist"}
          ],
          "pages": [
            {"path": "galleries/987560/1.jpg", "thumbnail": "galleries/987560/1t.jpg"},
            {"path": "galleries/987560/2.jpg", "thumbnail": "galleries/987560/2t.jpg"}
          ]
        }
        """.trimIndent(),
    )

    private val metadata = NHentaiSearchMetadata().apply {
        fillFrom(gallery, thumbServer = THUMBS, preferredTitle = NHentaiSearchMetadata.TITLE_TYPE_SHORT)
    }

    @Test
    fun `the three titles are kept`() {
        listOf(metadata.englishTitle, metadata.japaneseTitle, metadata.shortTitle) shouldBe
            listOf("English Title", "Japanese Title", "Pretty Title")
    }

    @Test
    fun `the cover falls back to the gallery thumbnail when there is no cover`() {
        metadata.coverImageUrl shouldBe "$THUMBS/galleries/987560/thumb.jpg"
    }

    @Test
    fun `the cover wins over the gallery thumbnail`() {
        val withCover = gallery.copy(cover = NhPage(path = "galleries/987560/cover.jpg"))

        NHentaiSearchMetadata().apply { fillFrom(withCover, THUMBS, NHentaiSearchMetadata.TITLE_TYPE_SHORT) }
            .coverImageUrl shouldBe "$THUMBS/galleries/987560/cover.jpg"
    }

    @Test
    fun `a category tag is virtual and a nameless tag is dropped`() {
        metadata.tags.map { Triple(it.namespace, it.name, it.type) } shouldBe listOf(
            Triple("category", "doujinshi", RaisedSearchMetadata.TAG_TYPE_VIRTUAL),
            Triple("tag", "full color", NHentaiSearchMetadata.TAG_TYPE_DEFAULT),
        )
    }

    @Test
    fun `a blank scanlator is dropped`() {
        metadata.scanlator shouldBe null
    }

    @Test
    fun `the page previews are the page thumbnails on the thumbnail host`() {
        nhPagePreviews(1, metadata.pageImagePreviewUrls, THUMBS).pagePreviews.map { it.index to it.imageUrl } shouldBe
            listOf(1 to "$THUMBS/galleries/987560/1t.jpg", 2 to "$THUMBS/galleries/987560/2t.jpg")
    }

    @Test
    fun `any title setting other than full shows the short title`() {
        val prefs = mockk<SharedPreferences> { every { getString(any(), any()) } returns "short" }

        nhPreferredTitle(prefs) shouldBe NHentaiSearchMetadata.TITLE_TYPE_SHORT
    }

    @Test
    fun `the servers named by the config are used`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body("""{"image_servers":["https://i9.example"],"thumb_servers":["$THUMBS"]}""".toResponseBody())
                .build()
        }.build()
        val servers = NhServers().apply { ensure(client, Headers.headersOf()) }

        servers.thumbServer shouldBe THUMBS
    }

    @Test
    fun `a failed config call falls back to nhentai's own thumbnail hosts`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        val servers = NhServers().apply { ensure(client, Headers.headersOf()) }

        servers.thumbServer shouldBeIn (1..4).map { "https://t$it.nhentai.net" }
    }

    private companion object {
        const val THUMBS = "https://t9.example"
    }
}
