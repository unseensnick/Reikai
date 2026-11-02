package reikai.data.track

import android.os.SystemClock
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.novellist.NovelList
import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDb
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.presentation.recents.EmittingPreferenceStore
import uy.kohesive.injekt.Injekt
import java.io.IOException
import java.net.UnknownHostException

/** The login shell both token trackers share, over the real trackers with nothing reaching a network. */
class TokenTrackerLoginTest {

    enum class Service(val build: () -> TokenTracker) {
        NOVEL_LIST({ NovelList(101) }),
        RANOBE_DB({ RanobeDb(102) }),
    }

    /** Answers as the account's profile; off, a request goes on to the tracker's own auth step. */
    private var answering = true

    private val offline = OkHttpClient.Builder()
        .dns { host -> throw UnknownHostException(host) }
        .addInterceptor { chain ->
            if (!answering) return@addInterceptor chain.proceed(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"username":"reader"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private val network = mockk<NetworkHelper>(relaxed = true) { every { client } returns offline }
    private val appScope = installTrackerTestGraph(network, EmittingPreferenceStore())

    // The trackers' rate limiter reads this clock, which a plain JVM test does not have.
    init {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
    }

    @AfterEach
    fun tearDown() {
        Injekt = appScope
        unmockkStatic(SystemClock::class)
    }

    @ParameterizedTest
    @EnumSource(Service::class)
    fun `a pasted token is stored without the spaces around it`(service: Service) = runTest {
        val tracker = service.build()

        tracker.login("  token\n")

        tracker.getPassword() shouldBe "token"
    }

    @ParameterizedTest
    @EnumSource(Service::class)
    fun `logout forgets the login`(service: Service) = runTest {
        val tracker = service.build()
        tracker.login("token")

        tracker.logout()

        (tracker.getUsername() to tracker.getPassword()) shouldBe ("" to "")
    }

    @ParameterizedTest
    @EnumSource(Service::class)
    fun `logout takes the credential back from the interceptor`(service: Service) = runTest {
        val tracker = service.build()
        tracker.login("token")
        tracker.logout()
        answering = false

        val failure = shouldThrow<IOException> { (tracker as DeletableTracker).delete(mockk(relaxed = true)) }

        generateSequence<Throwable>(failure) { it.cause }.any { it is TrackerSignedOutException } shouldBe true
    }
}
