package exh.favorites

import android.content.Context
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.util.system.toast
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.track.RemoteFirstRemoval
import reikai.presentation.track.trackerErrorMessage
import tachiyomi.domain.manga.model.Manga

/**
 * Removing a gallery from the library with "Also remove from favorites" ticked: the account goes first,
 * and a failure there keeps the gallery in the library and is told, so the user can retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EhGalleryRemovalTest {

    private val calls = mutableListOf<String>()
    private val toasts = mutableListOf<String?>()
    private val gallery = Manga.create().copy(id = 1L, url = "/g/123/abcdef/")

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

    private fun source(failure: Exception? = null) = mockk<EHentai> {
        every { name } returns "E-Hentai"
        coEvery { removeFavorites(any()) } answers {
            failure?.let { throw it }
            calls += "account removal ${firstArg<List<String>>()}"
        }
    }

    private val removeFromLibrary: suspend () -> Unit = { calls += "library removal" }

    @Test
    fun `a failed account removal keeps the gallery in the library`() = runTest {
        source(RATE_LIMITED).removeGallery(removal(), gallery, alsoFromAccount = true, removeFromLibrary)
        advanceUntilIdle()

        calls shouldBe emptyList()
    }

    @Test
    fun `a failed account removal is told by the source's name`() = runTest {
        source(RATE_LIMITED).removeGallery(removal(), gallery, alsoFromAccount = true, removeFromLibrary)
        advanceUntilIdle()

        toasts shouldBe listOf("E-Hentai failed")
    }

    @Test
    fun `the account removal goes before the library removal`() = runTest {
        source().removeGallery(removal(), gallery, alsoFromAccount = true, removeFromLibrary)
        advanceUntilIdle()

        calls shouldBe listOf("account removal [123]", "library removal")
    }

    @Test
    fun `a library-only removal leaves the account alone`() = runTest {
        source(RATE_LIMITED).removeGallery(removal(), gallery, alsoFromAccount = false, removeFromLibrary)
        advanceUntilIdle()

        calls shouldBe listOf("library removal")
    }

    private fun TestScope.removal() = RemoteFirstRemoval(mockk(relaxed = true), this)

    private companion object {
        const val TOAST = "eu.kanade.tachiyomi.util.system.ToastExtensionsKt"
        const val TRACKER_ERROR = "reikai.presentation.track.TrackerErrorMessageKt"
        val RATE_LIMITED = HttpException(429)
    }
}
