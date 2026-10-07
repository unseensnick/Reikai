package exh.md

import android.content.Context
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import java.io.File

/** An MDList search cover is cached like a browse cover, so reopening the search does not refetch it. */
class MangaDexTrackCoverTest {

    @TempDir
    lateinit var dir: File

    private val cache by lazy {
        DiskCache.Builder().directory(File(dir, "coil").toOkioPath()).maxSizeBytes(1024L * 1024L).build()
    }

    // Windows cannot delete the temp dir while the cache journal is open.
    @AfterEach
    fun closeCache() = cache.shutdown()

    @Test
    fun `a search cover shown twice is fetched from the network once`() = runTest {
        var requests = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("cover".toResponseBody())
                    .build()
            }
            .build()
        val context = mockk<Context> { every { getExternalFilesDir(any()) } returns dir }
        val imageLoader = mockk<ImageLoader> {
            every { diskCache } returns cache
            every { components } returns ComponentRegistry.Builder().add(MangaDexTrackCoverKeyer()).build()
        }
        val sourceManager = mockk<SourceManager> { coEvery { getOnlineSources() } returns emptyList() }
        val factory = MangaDexTrackCoverFactory(
            lazy { client },
            CoverCache(context),
            MangaCoverMetadata(UiPreferences(InMemoryPreferenceStore()), CoverCache(context)),
            SourcePreferences(InMemoryPreferenceStore()),
            ReikaiSourcePreferences(InMemoryPreferenceStore()),
            sourceManager,
        )
        val cover = MangaDexTrackCover("https://uploads.mangadex.org/covers/x/y.jpg")

        (factory.create(cover, Options(context), imageLoader).fetch() as SourceFetchResult).source.close()
        (factory.create(cover, Options(context), imageLoader).fetch() as SourceFetchResult).source.close()

        requests shouldBe 1
    }
}
