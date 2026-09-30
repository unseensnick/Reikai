package reikai.domain.track

import android.content.Context
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.system.toast
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.presentation.track.trackerErrorMessage

/**
 * The ordering every remote-first removal runs, and the scope it runs on: a screen closed while the
 * remote call is in flight must not cancel the local half once the remote one lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFirstRemovalTest {

    private val calls = mutableListOf<String>()
    private val toasts = mutableListOf<String?>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(TOAST, TRACKER_ERROR)
        every { any<Context>().toast(any<String>(), any(), any()) } answers {
            toasts += secondArg<String?>()
            mockk(relaxed = true)
        }
        every { any<Context>().trackerErrorMessage(any(), any()) } answers { "${secondArg<String>()} failed" }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(TOAST, TRACKER_ERROR)
        Dispatchers.resetMain()
    }

    @Test
    fun `the local step runs after a remote success when the screen that asked is gone`() = runTest {
        val removal = removal()
        val screen = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val remoteAnswers = CompletableDeferred<Unit>()
        screen.launch {
            removal.launch(
                name = "AniList",
                alsoRemote = true,
                remote = {
                    remoteAnswers.await()
                    calls += "remote"
                },
                local = { calls += "local" },
            )
        }
        runCurrent()

        screen.cancel()
        remoteAnswers.complete(Unit)
        advanceUntilIdle()

        calls shouldBe listOf("remote", "local")
    }

    @Test
    fun `a failed local step is told rather than thrown`() = runTest {
        removal().launch(name = "AniList", alsoRemote = false, remote = {}, local = { throw RATE_LIMITED })
        advanceUntilIdle()

        toasts shouldBe listOf("AniList failed")
    }

    private fun TestScope.removal() = RemoteFirstRemoval(mockk(relaxed = true), this)

    private companion object {
        const val TOAST = "eu.kanade.tachiyomi.util.system.ToastExtensionsKt"
        const val TRACKER_ERROR = "reikai.presentation.track.TrackerErrorMessageKt"
        val RATE_LIMITED = HttpException(429)
    }
}
