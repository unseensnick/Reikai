package exh.source

import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.AsmHentai
import eu.kanade.tachiyomi.source.online.all.HentaiFox
import eu.kanade.tachiyomi.source.online.all.Koharu
import eu.kanade.tachiyomi.source.online.all.Lanraragi
import eu.kanade.tachiyomi.source.online.all.NHentai
import eu.kanade.tachiyomi.source.online.english.EightMuses
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** A gallery wrapper's details refresh keeps what the extension set and the metadata does not carry. */
class LayeredMangaUpdateTest {

    // EightMuses reads the gallery path and Lanraragi builds its metadata url through android.net.Uri.
    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val text = firstArg<String>()
            val built = mockk<Uri>(relaxed = true) { every { this@mockk.toString() } returns text }
            mockk<Uri>(relaxed = true) {
                every { pathSegments } returns emptyList()
                every { buildUpon() } returns mockk(relaxed = true) { every { build() } returns built }
            }
        }
    }

    @AfterEach
    fun tearDown() = unmockkStatic(Uri::class)

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrappers")
    fun `a details refresh keeps the extension's fetch-once strategy`(
        name: String,
        wrap: (HttpSource) -> MetadataSource<*, *>,
        body: String,
    ) = runTest {
        val updated = refreshDetails(wrap, body)

        updated.manga.update_strategy shouldBe UpdateStrategy.ONLY_FETCH_ONCE
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gallerySites")
    fun `a details refresh keeps the extension's author`(
        name: String,
        wrap: (HttpSource) -> MetadataSource<*, *>,
        body: String,
    ) = runTest {
        val updated = refreshDetails(wrap, body) { author = "Circle Name" }

        updated.manga.author shouldBe "Circle Name"
    }

    @Test
    fun `Lanraragi reads its metadata from the archive api`() = runTest {
        val requested = mutableListOf<String>()

        refreshDetails({ Lanraragi(it, mockk(relaxed = true)) }, LANRARAGI_ARCHIVE, requested)

        requested shouldContain "/api/archives/$ARCHIVE_ID/metadata"
    }

    private suspend fun refreshDetails(
        wrap: (HttpSource) -> MetadataSource<*, *>,
        body: String,
        requested: MutableList<String> = mutableListOf(),
        fromExtension: SManga.() -> Unit = { update_strategy = UpdateStrategy.ONLY_FETCH_ONCE },
    ): SMangaUpdate {
        val delegate = mockk<HttpSource>(relaxed = true) {
            every { versionId } returns 1
            every { lang } returns "en"
            every { baseUrl } returns "https://example.org"
            every { headers } returns Headers.headersOf()
            every { client } returns OkHttpClient.Builder().addInterceptor { chain ->
                requested += chain.request().url.encodedPath
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("text/html".toMediaType())).build()
            }.build()
            every { mangaDetailsRequest(any()) } returns GET("https://example.org/reader?id=$ARCHIVE_ID")
            coEvery { getMangaUpdate(any(), any(), true, any()) } returns SMangaUpdate(
                SManga.create().apply {
                    title = "From the extension"
                    fromExtension()
                },
                emptyList(),
            )
        }
        val source = spyk(wrap(delegate)) {
            every { getMangaId } returns object : MetadataSource.GetMangaId {
                override suspend fun awaitId(url: String, sourceId: Long): Long? = null
            }
        }
        val stored = SManga.create().apply {
            url = "/reader?id=$ARCHIVE_ID"
            title = "Stored"
        }
        return source.getMangaUpdate(stored, emptyList(), fetchDetails = true, fetchChapters = false)
    }

    companion object {
        private const val ARCHIVE_ID = "0123456789abcdef0123456789abcdef01234567"
        private const val LANRARAGI_ARCHIVE = """{"arcid":"$ARCHIVE_ID","isnew":"false","tags":null,""" +
            """"summary":null,"title":"t","pagecount":1,"filename":"f","extension":"zip"}"""

        // The gallery sites whose metadata names no author, so the extension's stands.
        @JvmStatic
        fun gallerySites() = listOf(
            Arguments.of("HentaiFox", { d: HttpSource -> HentaiFox(d, mockk(relaxed = true)) }, "<html></html>"),
            Arguments.of("AsmHentai", { d: HttpSource -> AsmHentai(d, mockk(relaxed = true)) }, "<html></html>"),
            Arguments.of("Koharu", { d: HttpSource -> Koharu(d, mockk(relaxed = true)) }, "{}"),
        )

        @JvmStatic
        fun wrappers() = gallerySites() + listOf(
            Arguments.of("NHentai", { d: HttpSource -> NHentai(d, mockk(relaxed = true)) }, """{"id":1}"""),
            Arguments.of("EightMuses", { d: HttpSource -> EightMuses(d, mockk(relaxed = true)) }, "<html></html>"),
            Arguments.of("Lanraragi", { d: HttpSource -> Lanraragi(d, mockk(relaxed = true)) }, LANRARAGI_ARCHIVE),
        )
    }
}
