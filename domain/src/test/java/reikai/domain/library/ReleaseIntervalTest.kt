package reikai.domain.library

import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test

/** The re-predict and keep-user-interval rules manga's `FetchInterval` and the novel prediction both call. */
class ReleaseIntervalTest {

    private val zone = TimeZone.UTC
    private val window = 1_000L to 2_000L

    @Test
    fun `an unchanged list with a prediction inside the window is not predicted again`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 3, nextUpdate = 1_500L, window) shouldBe
            false
    }

    @Test
    fun `a refresh asked for by hand predicts again`() {
        ReleaseInterval.needsPrediction(manualFetch = true, fetchInterval = 3, nextUpdate = 1_500L, window) shouldBe
            true
    }

    @Test
    fun `an entry never predicted is predicted`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 0, nextUpdate = 1_500L, window) shouldBe
            true
    }

    @Test
    fun `a prediction fallen behind the window is predicted again`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 3, nextUpdate = 999L, window) shouldBe
            true
    }

    @Test
    fun `an interval the user set is kept`() {
        ReleaseInterval.userOrPredicted(-5) { 2 } shouldBe -5
    }

    @Test
    fun `an interval nobody set is predicted`() {
        ReleaseInterval.userOrPredicted(3) { 2 } shouldBe 2
    }

    @Test
    fun `an empty window means today's`() {
        val today = LocalDate(2026, 9, 17)

        ReleaseInterval.windowOrToday(0L to 0L, today, zone) shouldBe ReleaseInterval.window(today, zone)
    }

    @Test
    fun `a given window is kept`() {
        ReleaseInterval.windowOrToday(window, LocalDate(2026, 9, 17), zone) shouldBe window
    }
}
