package exh.source

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.AsmHentai
import eu.kanade.tachiyomi.source.online.all.HentaiFox
import eu.kanade.tachiyomi.source.online.all.Koharu
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** A gallery wrapper's details refresh keeps what the extension set and the metadata does not carry. */
class LayeredMangaUpdateTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrappers")
    fun `a details refresh keeps the extension's fetch-once strategy`(
        name: String,
        wrap: (HttpSource) -> MetadataSource<*, *>,
        body: String,
    ) = runTest {
        val delegate = mockk<HttpSource>(relaxed = true) {
            every { versionId } returns 1
            every { lang } returns "en"
            every { client } returns OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("text/html".toMediaType())).build()
            }.build()
            every { mangaDetailsRequest(any()) } returns GET("https://example.org/g/1/")
            coEvery { getMangaUpdate(any(), any(), true, any()) } returns SMangaUpdate(
                SManga.create().apply {
                    title = "From the extension"
                    update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
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
            url = "/g/1/"
            title = "Stored"
        }

        val updated = source.getMangaUpdate(stored, emptyList(), fetchDetails = true, fetchChapters = false)

        updated.manga.update_strategy shouldBe UpdateStrategy.ONLY_FETCH_ONCE
    }

    companion object {
        @JvmStatic
        fun wrappers() = listOf(
            Arguments.of("HentaiFox", { d: HttpSource -> HentaiFox(d, mockk(relaxed = true)) }, "<html></html>"),
            Arguments.of("AsmHentai", { d: HttpSource -> AsmHentai(d, mockk(relaxed = true)) }, "<html></html>"),
            Arguments.of("Koharu", { d: HttpSource -> Koharu(d, mockk(relaxed = true)) }, "{}"),
        )
    }
}
