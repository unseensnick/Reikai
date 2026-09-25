package reikai.novel.network

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.novel.source.ireader.IReaderSourceHolder
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import ireader.core.source.HttpSource as IReaderHttpSource

/** Every novel picture is fetched with its own source's client and headers, whatever format it comes in. */
class NovelImageRequestsTest {

    @Test
    fun `an LNReader source's site is its images' Referer`() {
        lnImageHeaders("UA", "https://site.example/", emptyMap())["Referer"] shouldBe "https://site.example/"
    }

    @Test
    fun `a plugin's own image header wins over the default, whatever its case`() {
        val headers = lnImageHeaders("UA", "https://site.example/", mapOf("referer" to "https://cdn.example/"))

        headers.values("Referer") shouldBe listOf("https://cdn.example/")
    }

    @Test
    fun `a plugin's own user agent wins over the device's`() {
        lnImageHeaders("device", null, mapOf("User-Agent" to "desktop"))["User-Agent"] shouldBe "desktop"
    }

    @Test
    fun `a header no request can carry is dropped and the rest are kept`() {
        val headers = lnImageHeaders("UA", null, mapOf("Bad\nName" to "x", "X-Image" to "1"))

        headers["X-Image"] shouldBe "1"
    }

    @Test
    fun `an LNReader source is answered from the record its last load saved`() = runTest {
        val identity =
            LnSourceIdentity(name = "P", site = "https://site.example/", imageHeaders = mapOf("X-Image" to "1"))

        val headers = requests(seen = mapOf("p" to identity)).forSource("p").headers

        (headers["Referer"] to headers["X-Image"]) shouldBe ("https://site.example/" to "1")
    }

    @Test
    fun `an LNReader source does not wait for the extension scan`() = runTest {
        val identity = LnSourceIdentity(name = "P", site = "https://site.example/")
        val requests = requests(seen = mapOf("p" to identity), scanDone = false)

        requests.forSource("p").headers["Referer"] shouldBe "https://site.example/"
    }

    @Test
    fun `an APK source uses its own headers`() = runTest {
        val source = mockk<HttpSource> {
            every { id } returns 42L
            every { baseUrl } returns "https://apk.example"
            every { headers } returns headersOf("Referer", "https://apk.example/")
            every { client } returns OkHttpClient()
        }
        val extension = mockk<Extension.Loaded> { every { sources } returns listOf(source) }

        val headers = requests(loaded = listOf(extension)).forSource("tachiyomi:42").headers

        headers["Referer"] shouldBe "https://apk.example/"
    }

    @Test
    fun `a chapter picture on the source's own site gets its headers, a subdomain included`() = runTest {
        requests(loaded = listOf(apkWithToken())).forSource("tachiyomi:42")
            .forUrl("https://cdn.apk.example/p.jpg").headers["X-Token"] shouldBe "secret"
    }

    @Test
    fun `a chapter picture on another site never gets the source's headers`() = runTest {
        requests(loaded = listOf(apkWithToken())).forSource("tachiyomi:42")
            .forUrl("https://attacker.example/p.jpg").headers["X-Token"] shouldBe null
    }

    @Test
    fun `a chapter picture on another site still names the source's site as Referer`() = runTest {
        requests(loaded = listOf(apkWithToken())).forSource("tachiyomi:42")
            .forUrl("https://images.example/p.jpg").headers["Referer"] shouldBe "https://apk.example"
    }

    @Test
    fun `a plugin's own image header stays on its site`() = runTest {
        val identity =
            LnSourceIdentity(name = "P", site = "https://site.example/", imageHeaders = mapOf("X-Image" to "1"))

        requests(seen = mapOf("p" to identity)).forSource("p")
            .forUrl("https://elsewhere.example/p.jpg").headers["X-Image"] shouldBe null
    }

    private fun apkWithToken(): Extension.Loaded {
        val source = mockk<HttpSource> {
            every { id } returns 42L
            every { baseUrl } returns "https://apk.example"
            every { headers } returns headersOf("X-Token", "secret")
            every { client } returns OkHttpClient()
        }
        return mockk { every { sources } returns listOf(source) }
    }

    @Test
    fun `an IReader source uses the headers its cover request carries`() = runTest {
        val catalogue = mockk<IReaderHttpSource> {
            every { id } returns 42L
            every { baseUrl } returns "https://ir.example"
            every { getCoverRequest(any()) } answers {
                HttpClient() to HttpRequestBuilder().apply { headers.append("Referer", "https://ir.example/") }
            }
        }
        val extension = mockk<Extension.Loaded> { every { sources } returns listOf(IReaderSourceHolder(catalogue)) }

        val headers = requests(loaded = listOf(extension)).forSource("ireader:42").headers

        headers["Referer"] shouldBe "https://ir.example/"
    }

    @Test
    fun `an IReader source is not taken for a tachiyomi one with the same number`() = runTest {
        val catalogue = mockk<IReaderHttpSource> {
            every { id } returns 42L
            every { baseUrl } returns "https://ir.example"
            every { getCoverRequest(any()) } answers {
                HttpClient() to HttpRequestBuilder().apply { headers.append("Referer", "https://ir.example/") }
            }
        }
        val extension = mockk<Extension.Loaded> { every { sources } returns listOf(IReaderSourceHolder(catalogue)) }

        requests(loaded = listOf(extension)).forSource("tachiyomi:42").headers["Referer"] shouldBe null
    }

    @Test
    fun `the WebView gets an APK source's own headers`() = runTest {
        requests(loaded = listOf(apkWithToken())).webViewHeaders("tachiyomi:42") shouldBe
            mapOf("x-token" to "secret")
    }

    @Test
    fun `the WebView gets the headers an IReader source's cover request carries`() = runTest {
        val catalogue = mockk<IReaderHttpSource> {
            every { id } returns 42L
            every { baseUrl } returns "https://ir.example"
            every { getCoverRequest(any()) } answers {
                HttpClient() to HttpRequestBuilder().apply { headers.append("Referer", "https://ir.example/") }
            }
        }
        val extension = mockk<Extension.Loaded> { every { sources } returns listOf(IReaderSourceHolder(catalogue)) }

        requests(loaded = listOf(extension)).webViewHeaders("ireader:42") shouldBe
            mapOf("referer" to "https://ir.example/")
    }

    @Test
    fun `the WebView gets no headers for an LNReader source, without waiting for the extension scan`() = runTest {
        requests(scanDone = false).webViewHeaders("p") shouldBe emptyMap()
    }

    private fun requests(
        seen: Map<String, LnSourceIdentity> = emptyMap(),
        loaded: List<Extension.Loaded> = emptyList(),
        scanDone: Boolean = true,
    ) = NovelImageRequests(
        context = mockk<Context>(relaxed = true),
        network = mockk<NetworkHelper> { every { client } returns OkHttpClient() },
        novelPreferences = NovelPreferences(
            InMemoryPreferenceStore(sequenceOf(InMemoryPreference("ln_seen_novel_sources", seen, emptyMap()))),
        ),
        extensionManager = mockk<ExtensionManager> {
            every { loadedNovelExtensionsFlow } returns if (scanDone) flowOf(loaded) else flow { awaitCancellation() }
        },
    )
}
