package reikai.novel.network

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

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
    fun `an APK source uses its own headers`() = runTest {
        val source = mockk<HttpSource> {
            every { id } returns 42L
            every { headers } returns headersOf("Referer", "https://apk.example/")
            every { client } returns OkHttpClient()
        }
        val extension = mockk<Extension.Loaded> { every { sources } returns listOf(source) }

        val headers = requests(loaded = listOf(extension)).forSource("tachiyomi:42").headers

        headers["Referer"] shouldBe "https://apk.example/"
    }

    private fun requests(
        seen: Map<String, LnSourceIdentity> = emptyMap(),
        loaded: List<Extension.Loaded> = emptyList(),
    ) = NovelImageRequests(
        context = mockk<Context>(relaxed = true),
        network = mockk<NetworkHelper> { every { client } returns OkHttpClient() },
        novelPreferences = NovelPreferences(
            InMemoryPreferenceStore(sequenceOf(InMemoryPreference("ln_seen_novel_sources", seen, emptyMap()))),
        ),
        extensionManager = mockk<ExtensionManager> { every { loadedNovelExtensionsFlow } returns flowOf(loaded) },
    )
}
