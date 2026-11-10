package reikai.data.track

import android.webkit.CookieManager
import eu.kanade.tachiyomi.data.track.CookieLoginTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldExist
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SignOutCase(private val label: String, val tracker: (TrackerManager) -> Tracker) {
    override fun toString() = label
}

/**
 * Signing out of a tracker the WebView signs into also signs the in-app browser out of its site, so
 * the next WebView sign-in shows the form instead of silently capturing the old account again.
 */
class CookieLoginSignOutTest {

    private lateinit var trackers: TrackerManager
    private val expired = mutableListOf<Pair<String, String>>()

    @BeforeEach
    fun setUp() {
        // The jar reads the CookieManager singleton as it is built, so the mock goes in first.
        mockkStatic(CookieManager::class)
        val manager = mockk<CookieManager>(relaxed = true) {
            every { getCookie(any()) } returns "session=a-live-account"
            every { setCookie(any(), any()) } answers { expired += firstArg<String>() to secondArg<String>() }
        }
        every { CookieManager.getInstance() } returns manager
        val jar = AndroidCookieJar()
        trackers =
            trackerTestManager(
                trackerTestDependencies(
                    mockk<NetworkHelper>(relaxed = true) {
                        every { cookieJar } returns
                            jar
                    },
                ),
            )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(CookieManager::class)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `signing out expires the site's session cookie`(case: SignOutCase) {
        val tracker = case.tracker(trackers)
        val site = (tracker as CookieLoginTracker).cookieDomain
        tracker.logout()
        expired.shouldExist { (url, cookie) -> url.startsWith(site) && cookie.startsWith("session=;") }
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            SignOutCase("RanobeDb") { it.ranobeDb },
            SignOutCase("NovelList") { it.novelList },
            SignOutCase("NovelUpdates") { it.novelUpdates },
        )
    }
}
