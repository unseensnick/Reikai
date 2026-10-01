package exh.md.network

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import exh.md.utils.MdApi
import exh.md.utils.MdUtil
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.presentation.recents.EmittingPreferenceStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Refreshing an expired MDList token keeps the saved login unless MangaDex rejects the refresh. */
class MangaDexAuthInterceptorTest {

    private val mdList = mockk<MdList> {
        every { id } returns 60L
        every { name } returns "MDList"
    }
    private val trackPreferences = TrackPreferences(EmittingPreferenceStore())
    private val expired = MdUtil.jsonParser.encodeToString(MALOAuth("refresh", "access", expiresIn = 0, createdAt = 0))

    private fun request(url: String) = Request.Builder().url(url).build()

    private fun response(request: Request, code: Int, body: String = "") = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body(body.toResponseBody())
        .build()

    /** Waits until [thread] is parked on a monitor, so it has read the stale token before the refresh lands. */
    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait()
    }

    private fun chain(refresh: (Request) -> Response, call: (Request) -> Response = { response(it, 200) }) =
        mockk<Interceptor.Chain> {
            every { request() } returns request(MdApi.userFollows)
            every { proceed(any()) } answers {
                val sent = firstArg<Request>()
                if (sent.url.toString() == MdApi.baseAuthUrl + MdApi.token) refresh(sent) else call(sent)
            }
        }

    /** Runs one API call whose token refresh [answers] the way the test needs; returns the saved login. */
    private fun savedLoginAfter(answers: (Request) -> Response): String {
        trackPreferences.trackToken(mdList).set(expired)
        runCatching { MangaDexAuthInterceptor(trackPreferences, mdList).intercept(chain(answers)) }
        return trackPreferences.trackToken(mdList).get()
    }

    @Test
    @DisplayName("a refresh lost to the network keeps the saved login")
    fun transportFailureKeepsLogin() {
        savedLoginAfter { throw IOException("offline") } shouldBe expired
    }

    @Test
    @DisplayName("a refresh the server fails to answer keeps the saved login")
    fun serverErrorKeepsLogin() {
        savedLoginAfter { response(it, 503) } shouldBe expired
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401])
    @DisplayName("a refresh MangaDex rejects clears the saved login")
    fun rejectedRefreshClearsLogin(code: Int) {
        savedLoginAfter { response(it, code) } shouldBe ""
    }

    @Test
    @DisplayName("a request that waited on a refresh sends the token that refresh fetched")
    fun waitingRequestTakesFetchedToken() {
        trackPreferences.trackToken(mdList).set(expired)
        val interceptor = MangaDexAuthInterceptor(trackPreferences, mdList)
        val refreshes = AtomicInteger()
        val firstRefreshStarted = CountDownLatch(1)
        val releaseFirstRefresh = CountDownLatch(1)
        val refresh = { sent: Request ->
            val fresh = MALOAuth("refresh", "fresh${refreshes.incrementAndGet()}", expiresIn = 3600)
            firstRefreshStarted.countDown()
            releaseFirstRefresh.await(5, TimeUnit.SECONDS)
            response(sent, 200, MdUtil.jsonParser.encodeToString(fresh))
        }
        val waiterBearer = AtomicReference<String>()
        val first = thread { interceptor.intercept(chain(refresh)) }
        firstRefreshStarted.await(5, TimeUnit.SECONDS)
        val waiter = thread {
            interceptor.intercept(
                chain(refresh) { sent ->
                    response(sent, 200).also { waiterBearer.set(sent.header("Authorization")) }
                },
            )
        }
        awaitBlocked(waiter)
        releaseFirstRefresh.countDown()
        first.join(5000)
        waiter.join(5000)

        waiterBearer.get() shouldBe "Bearer fresh1"
    }
}
