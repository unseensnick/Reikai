package eu.kanade.tachiyomi.source.online.all

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.metadata.metadata.HitomiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.util.linkUri
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** A Hitomi gallery script parses into namespaced metadata, and a link imports as its gallery. */
class HitomiTest {

    private val hosts = mutableListOf<String>()

    // Serves the galleries script for whichever id was asked, as the extension's api does, and every
    // small thumbnail but the gif's, which only the b host has.
    private val delegate = mockk<HttpSource>(relaxed = true) {
        every { versionId } returns 1
        every { lang } returns "all"
        every { headers } returns Headers.headersOf()
        every { client } returns OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            hosts += url.host
            val id = GALLERY_SCRIPT.find(url.encodedPath)?.groupValues?.get(1)
            val thumbnail = url.encodedPath.startsWith("/avifsmalltn/") &&
                !(url.host.startsWith("atn.") && url.encodedPath.contains(HASH_GIF))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (id != null || thumbnail) 200 else 404).message("")
                .body(gallery(id.orEmpty()).toResponseBody("application/javascript".toMediaType())).build()
        }.build()
        // The extension reads the gallery id after its url's last dash.
        every { mangaDetailsRequest(any()) } answers {
            val id = firstArg<SManga>().url.substringAfterLast("-").substringBefore(".")
            GET("https://ltn.example/galleries/$id.js")
        }
    }

    private val source = spyk(Hitomi(delegate, mockk(relaxed = true))) {
        every { getMangaId } returns object : MetadataSource.GetMangaId {
            override suspend fun awaitId(url: String, sourceId: Long): Long? = null
        }
    }

    private suspend fun parsed(script: String = gallery("$ID")) = HitomiSearchMetadata().also {
        source.parseIntoMetadata(it, script)
    }

    @Test
    fun `the gallery id comes from the script`() = runTest {
        parsed("""var galleryinfo = {"id":"$ID"}""").hlId shouldBe ID
    }

    @Test
    fun `the gallery id falls back to the gallery url`() = runTest {
        parsed("""var galleryinfo = {"galleryurl":"/manga/a-title-$ID.html"}""").hlId shouldBe ID
    }

    @Test
    fun `the title comes from the script`() = runTest {
        parsed().title shouldBe "Gallery Title"
    }

    @Test
    fun `the gallery type is kept`() = runTest {
        parsed().galleryType shouldBe "doujinshi"
    }

    @Test
    fun `the language is kept`() = runTest {
        parsed().language shouldBe "english"
    }

    @Test
    fun `each file is a page, its hash and gif flag kept`() = runTest {
        parsed().images.map { it.hash to it.isGif } shouldBe listOf(HASH_JPG to false, HASH_GIF to true)
    }

    @Test
    fun `the upload date keeps the site's utc offset`() = runTest {
        parsed().uploadDate shouldBe 1709478000000L
    }

    @Test
    fun `the cover is the extension's loopback thumbnail of the first page`() = runTest {
        parsed().thumbnailUrl shouldBe "https://127.0.0.1/?is_thumbnail=true&is_gif=false#$HASH_JPG"
    }

    @ParameterizedTest(name = "{0}:{1}")
    @MethodSource("namespacedTags")
    fun `a gallery field is stored under its namespace`(namespace: String, name: String) = runTest {
        parsed().tags shouldContain RaisedTag(namespace, name, 0)
    }

    @Test
    fun `a script missing every other field still parses`() = runTest {
        parsed("""var galleryinfo = {"title":"Only a title"}""").title shouldBe "Only a title"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("links")
    fun `a link maps to its gallery's own url`(url: String, expected: String?) = runTest {
        source.mapUrlToMangaUrl(linkUri(url)) shouldBe expected
    }

    // The site keeps a big thumbnail for the cover alone, so a preview is each page's small one.
    @Test
    fun `each preview is its page's small thumbnail on the extension's cdn`() = runTest {
        val manga = SManga.create().apply { url = galleryUrl("$ID") }

        source.getPagePreviewList(manga, emptyList(), 1).pagePreviews.map { it.imageUrl } shouldBe listOf(
            "https://atn.example/avifsmalltn/a/12/$HASH_JPG.avif",
            "https://atn.example/avifsmalltn/d/fe/$HASH_GIF.avif",
        )
    }

    @Test
    fun `a thumbnail the a host lacks is fetched from the b host`() = runTest {
        val url = "https://atn.example/avifsmalltn/d/fe/$HASH_GIF.avif"

        source.fetchPreviewImage(PagePreviewInfo(2, url), null).code shouldBe 200
    }

    @Test
    fun `a thumbnail the a host has is fetched once`() = runTest {
        source.fetchPreviewImage(PagePreviewInfo(1, "https://atn.example/avifsmalltn/a/12/$HASH_JPG.avif"), null)
            .close()

        hosts shouldBe listOf("atn.example")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("searches")
    fun `a tag chip searches the site's own key`(namespace: String, tag: String, expected: String) {
        source.tagSearchQuery(namespace, tag) shouldBe expected
    }

    companion object {
        private const val ID = 2345678
        private val GALLERY_SCRIPT = Regex("""^/galleries/(\d+)\.js$""")
        private const val HASH_JPG = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abc12a"
        private const val HASH_GIF = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543fed"

        private fun galleryUrl(id: String) = "/doujinshi/gallery-title-english-$id.html"

        // Shaped like the site's galleries/<id>.js; the flags arrive as "1", 1 or "".
        private fun gallery(id: String) = """var galleryinfo = {"id":"$id","galleryurl":"${galleryUrl(id)}",""" +
            """"title":"Gallery Title","japanese_title":null,"date":"2024-03-03 10:00:00-05",""" +
            """"type":"doujinshi","language":"english","language_localname":"English",""" +
            """"tags":[{"tag":"big breasts","female":"1","male":"","url":"/tag/x.html"},""" +
            """{"tag":"muscle","female":"","male":1,"url":"/tag/y.html"},""" +
            """{"tag":"full color","female":"","male":"","url":"/tag/z.html"}],""" +
            """"artists":[{"artist":"someone","url":"/artist/x.html"}],""" +
            """"groups":[{"group":"some circle","url":"/group/x.html"}],""" +
            """"parodys":[{"parody":"original","url":"/series/x.html"}],""" +
            """"characters":[{"character":"heroine","url":"/character/x.html"}],""" +
            """"files":[{"hash":"$HASH_JPG","name":"01.jpg","hasavif":1,"width":1,"height":1},""" +
            """{"hash":"$HASH_GIF","name":"02.gif","hasavif":0,"width":1,"height":1}]}"""

        @JvmStatic
        fun namespacedTags() = listOf(
            Arguments.of("artist", "someone"),
            Arguments.of("group", "some circle"),
            Arguments.of("parody", "original"),
            Arguments.of("character", "heroine"),
            Arguments.of("female", "big breasts"),
            Arguments.of("male", "muscle"),
            Arguments.of("tags", "full color"),
            Arguments.of("category", "doujinshi"),
            Arguments.of("language", "english"),
        )

        @JvmStatic
        fun links() = listOf(
            Arguments.of("https://hitomi.la/reader/$ID.html", galleryUrl("$ID")),
            Arguments.of("https://hitomi.la/galleries/$ID.html", galleryUrl("$ID")),
            Arguments.of("https://www.hitomi.la/doujinshi/gallery-title-english-$ID.html", galleryUrl("$ID")),
            Arguments.of("https://hitomi.la/reader/$ID.html?page=3", galleryUrl("$ID")),
            Arguments.of("https://hitomi.la/tag/female:big_breasts-all.html", null),
            // Digits in a non-gallery page's name are not a gallery id.
            Arguments.of("https://hitomi.la/series/nier-2b-all.html", null),
        )

        @JvmStatic
        fun searches() = listOf(
            Arguments.of("tags", "Full Color", "tag:full_color"),
            Arguments.of("parody", "original", "series:original"),
            Arguments.of("category", "doujinshi", "type:doujinshi"),
            Arguments.of("artist", "someone", "artist:someone"),
        )
    }
}
