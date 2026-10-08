package eu.kanade.tachiyomi.ui.setting.track

import android.content.Context
import eu.kanade.tachiyomi.data.track.CookieLoginTracker
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.track.TrackerSignedOutException
import reikai.presentation.track.trackerErrorMessage

/** A refused browser sign-in tells the user why in the words every other tracker failure uses. */
class CookieLoginFailureTest {

    private val context = mockk<Context>()
    private val tracker = mockk<Tracker>(relaxed = true) { every { name } returns "Novel Updates" }

    @BeforeEach
    fun setUp() {
        mockkStatic(TRACKER_ERROR)
        every { any<Context>().trackerErrorMessage(any(), any()) } answers { "Log in to ${secondArg<String>()} again" }
    }

    @AfterEach
    fun tearDown() = unmockkStatic(TRACKER_ERROR)

    @Test
    fun `a refused sign-in is told in the shared tracker wording, not the raw exception`() = runTest {
        val cookieLogin = mockk<CookieLoginTracker> {
            coEvery { loginWithCookie(any()) } throws TrackerSignedOutException("Novel Updates")
        }

        context.cookieLoginFailure(tracker, cookieLogin, "session=x") shouldBe "Log in to Novel Updates again"
    }

    private companion object {
        const val TRACKER_ERROR = "reikai.presentation.track.TrackerErrorMessageKt"
    }
}
