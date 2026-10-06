package reikai.presentation.track

import android.content.Context
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.UnknownHostException

/** Several trackers failing one call for the same reason, offline being the usual one, read as one line. */
class TrackerFailuresMessageTest {

    private val context = mockk<Context>()

    @BeforeEach
    fun setUp() {
        mockkStatic(TRACKER_ERROR)
        every { any<Context>().trackerErrorMessage(any(), any()) } returns "No Internet connection"
    }

    @AfterEach
    fun tearDown() = unmockkStatic(TRACKER_ERROR)

    @Test
    fun `trackers failing for one reason are told once`() {
        val failed = listOf(tracker("AniList"), tracker("Kitsu")).map { it to UnknownHostException() }
        context.trackerFailuresMessage(failed) shouldBe "No Internet connection"
    }

    private fun tracker(name: String) = mockk<Tracker> { every { this@mockk.name } returns name }

    private companion object {
        const val TRACKER_ERROR = "reikai.presentation.track.TrackerErrorMessageKt"
    }
}
