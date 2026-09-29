package reikai.data.coil

import android.content.Context
import coil3.ImageLoader
import coil3.request.Options
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

/**
 * Manga and novel covers share one fetcher, and each content type hands it the client and headers its
 * source resolves; a cover host that refuses a request without the source's Referer depends on it.
 */
class CoverRequestClientTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `a cover is requested with the headers its source resolves`() = runTest {
        var sent: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sent = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("cover".toResponseBody())
                    .build()
            }
            .build()

        fetcher(CoverRequestClient(client, Headers.headersOf("Referer", SITE))).fetch()

        sent?.header("Referer") shouldBe SITE
    }

    private fun fetcher(client: CoverRequestClient): MangaCoverFetcher {
        val context = mockk<Context> { every { getExternalFilesDir(any()) } returns dir }
        val imageLoader = mockk<ImageLoader> { every { diskCache } returns null }
        return MangaCoverFetcher(
            url = "$SITE/cover.jpg",
            isLibraryManga = false,
            mangaCover = null,
            options = Options(context),
            coverFileLazy = lazy { null },
            customCoverFileLazy = lazy { File(dir, "custom") },
            diskCacheKeyLazy = lazy { "cover" },
            getClient = { client },
            imageLoader = imageLoader,
            mangaCoverMetadata = MangaCoverMetadata(UiPreferences(InMemoryPreferenceStore()), CoverCache(context)),
        )
    }

    private companion object {
        const val SITE = "https://example.org"
    }
}
