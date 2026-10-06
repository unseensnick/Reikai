package reikai.data.track

import android.net.Uri
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.lang.htmlDecode
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

class SearchKindCase(
    private val label: String,
    val search: suspend (Tracker) -> List<Long>,
    val tracker: () -> Tracker,
    val expected: List<Long>,
) {
    override fun toString() = label
}

/**
 * A tracker sheet offers only its own kind on every search path the tracker filters itself: series 1
 * is a manga and series 2 a light novel, both titled Overlord. MangaUpdates' manga title search is
 * left out, since the server applies its type filter there and a fake server would only echo it back.
 */
class TrackerSearchKindTest {

    private lateinit var appScope: InjektScope

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { fakeUri(firstArg()) }
        // MangaUpdates decodes titles through android.text.Html, another stub on this classpath.
        mockkStatic(HTML_DECODE)
        every { any<String>().htmlDecode() } answers { firstArg() }
        val client = OkHttpClient.Builder().addInterceptor(FakeTrackerServer).build()
        appScope =
            installTrackerTestGraph(mockk<NetworkHelper>(relaxed = true) { every { this@mockk.client } returns client })
    }

    @AfterEach
    fun tearDown() {
        Injekt = appScope
        unmockkStatic(Uri::class)
        unmockkStatic(HTML_DECODE)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a search offers only the sheet's own kind`(case: SearchKindCase) = runTest {
        case.search(case.tracker()) shouldBe case.expected
    }

    companion object {
        private const val HTML_DECODE = "eu.kanade.tachiyomi.util.lang.StringExtensionsKt"

        private fun manga(query: String): suspend (Tracker) -> List<Long> = { t ->
            t.search(query).map { it.remote_id }
        }
        private fun novel(query: String): suspend (Tracker) -> List<Long> =
            { t -> t.searchNovel(query).map { it.remote_id } }

        private val mal = { MyAnimeList(1) }
        private val mu = { MangaUpdates(7) }

        @JvmStatic
        fun cases() = listOf(
            SearchKindCase("MAL manga, title", manga("Overlord"), mal, listOf(1L)),
            SearchKindCase("MAL manga, id of the manga", manga("id:1"), mal, listOf(1L)),
            SearchKindCase("MAL manga, id of the novel", manga("id:2"), mal, emptyList()),
            SearchKindCase("MAL manga, my list", manga("my:Overlord"), mal, listOf(1L)),
            SearchKindCase("MAL novel, title", novel("Overlord"), mal, listOf(2L)),
            SearchKindCase("MAL novel, id of the novel", novel("id:2"), mal, listOf(2L)),
            SearchKindCase("MAL novel, id of the manga", novel("id:1"), mal, emptyList()),
            SearchKindCase("MAL novel, my list", novel("my:Overlord"), mal, listOf(2L)),
            SearchKindCase("MangaUpdates manga, id of the manga", manga("id:1"), mu, listOf(1L)),
            SearchKindCase("MangaUpdates manga, id of the novel", manga("id:2"), mu, emptyList()),
            SearchKindCase("MangaUpdates novel, title", novel("Overlord"), mu, listOf(2L)),
            SearchKindCase("MangaUpdates novel, id of the novel", novel("id:2"), mu, listOf(2L)),
            SearchKindCase("MangaUpdates novel, id of the manga", novel("id:1"), mu, emptyList()),
        )
    }
}

/** Answers MyAnimeList and MangaUpdates calls before any login interceptor runs. */
private object FakeTrackerServer : Interceptor {
    private fun malNode(id: Long, type: String) =
        """{"id":$id,"title":"Overlord","num_chapters":0,"status":"finished","media_type":"$type"}"""

    private fun muRecord(id: Long, type: String) = """{"series_id":$id,"title":"Overlord","type":"$type"}"""

    private val malList = """{"data":[{"node":${malNode(
        1,
        "manga",
    )}},{"node":${malNode(2, "light_novel")}}],"paging":{}}"""
    private val muList = """{"results":[{"record":${muRecord(1, "Manga")}},{"record":${muRecord(2, "Novel")}}]}"""

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = when (request.url.encodedPath) {
            "/v2/manga", "/v2/users/@me/mangalist" -> malList
            "/v2/manga/1" -> malNode(1, "manga")
            "/v2/manga/2" -> malNode(2, "light_novel")
            "/v1/series/search" -> muList
            "/v1/series/1" -> muRecord(1, "Manga")
            "/v1/series/2" -> muRecord(2, "Novel")
            else -> error("unexpected ${request.url}")
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }
}

/** MyAnimeList builds its URLs through android.net.Uri, which the JVM test classpath only stubs. */
private fun fakeUri(url: String): Uri = mockk {
    every { this@mockk.toString() } returns url
    every { buildUpon() } answers { fakeUriBuilder(url.toHttpUrl().newBuilder()) }
}

private fun fakeUriBuilder(url: HttpUrl.Builder): Uri.Builder = mockk {
    every { appendQueryParameter(any(), any()) } answers {
        url.addQueryParameter(firstArg(), secondArg())
        this@mockk
    }
    every { appendPath(any()) } answers {
        url.addPathSegment(firstArg())
        this@mockk
    }
    every { build() } answers { fakeUri(url.build().toString()) }
}
