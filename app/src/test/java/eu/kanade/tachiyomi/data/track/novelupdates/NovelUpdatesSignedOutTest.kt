package eu.kanade.tachiyomi.data.track.novelupdates

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

/** A logged-out reading-list page still loads, so its missing lists are what reads as signed out. */
class NovelUpdatesSignedOutTest {

    private val loggedOutPage = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<html><body></body></html>".toResponseBody())
                .build()
        }
        .build()

    private val appScope =
        installTrackerTestGraph(mockk<NetworkHelper>(relaxed = true) { every { client } returns loggedOutPage })

    @AfterEach
    fun tearDown() {
        Injekt = appScope
    }

    @Test
    fun `a session showing no reading lists reads as signed out`() = runTest {
        val error = runCatching { NovelUpdates(102).loginWithCookie("wordpress_logged_in_x=y") }.exceptionOrNull()
        TrackerError.of(error!!, isOnline = true) shouldBe TrackerError.SignedOut
    }
}
