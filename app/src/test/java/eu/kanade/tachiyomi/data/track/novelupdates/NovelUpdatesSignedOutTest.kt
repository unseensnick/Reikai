package eu.kanade.tachiyomi.data.track.novelupdates

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.data.track.installTrackerTestGraph
import reikai.presentation.track.TrackerError
import uy.kohesive.injekt.Injekt

/**
 * A lapsed session still gets pages, only anonymous ones, so what they lack is what reads as signed
 * out. Each test sets what the site answers; the defaults are a signed-in session's.
 */
class NovelUpdatesSignedOutTest {

    private var seriesPage = "<div class='seriestitlenu'>X</div>" +
        "<div class='sticon'><span class='sttitle'><a href='/reading-list/?list=0'>Reading</a></span></div>"
    private var listPage = "<html><body></body></html>"
    private var notesCode = 200
    private var notesBody = """{"notes":"","tags":""}0"""

    private val site = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val (code, body) = when {
                path.startsWith("/wp-admin/") -> notesCode to notesBody
                path.startsWith("/reading-list/") -> 200 to listPage
                else -> 200 to seriesPage
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Bad Request")
                .body(body.toResponseBody())
                .build()
        }
        .build()

    private val appScope =
        installTrackerTestGraph(mockk<NetworkHelper>(relaxed = true) { every { client } returns site })

    private val track = Track.create(TrackerManager.NOVELUPDATES).apply { remote_id = 99L }

    @AfterEach
    fun tearDown() {
        Injekt = appScope
    }

    private fun kindOf(error: Throwable?) = error?.let { TrackerError.of(it, isOnline = true) }

    @Test
    fun `a session showing no reading lists reads as signed out`() = runTest {
        val error = runCatching {
            NovelUpdates(TrackerManager.NOVELUPDATES).loginWithCookie("wordpress_logged_in_x=y")
        }.exceptionOrNull()

        kindOf(error) shouldBe TrackerError.SignedOut
    }

    /** WordPress answers an action with no handler for the caller, an anonymous one here, with a 400 and `0`. */
    @Test
    fun `the anonymous reply to the notes read reads as signed out`() = runTest {
        notesCode = 400
        notesBody = "0"

        val error = runCatching { NovelUpdates(TrackerManager.NOVELUPDATES).refresh(track) }.exceptionOrNull()

        kindOf(error) shouldBe TrackerError.SignedOut
    }

    @Test
    fun `a series page without its reading-list panel reads as signed out`() = runTest {
        seriesPage = "<div class='seriestitlenu'>X</div>"

        val error = runCatching { NovelUpdates(TrackerManager.NOVELUPDATES).refresh(track) }.exceptionOrNull()

        kindOf(error) shouldBe TrackerError.SignedOut
    }

    @Test
    fun `the list picker reads a page with no reading lists as signed out`() = runTest {
        val error = runCatching { NovelUpdates(TrackerManager.NOVELUPDATES).readingLists() }.exceptionOrNull()

        kindOf(error) shouldBe TrackerError.SignedOut
    }
}
