package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SeriesCompletionsTest {

    @Test
    @DisplayName("each finished chapter counts toward its own series")
    fun countsPerSeries() {
        val completions = SeriesCompletions()

        completions.record(1)
        completions.record(1)
        completions.record(2)

        completions.counts.value shouldBe mapOf(1L to 2, 2L to 1)
    }

    @Test
    @DisplayName("a series that left the queue starts from zero when queued again")
    fun leftSeriesIsForgotten() {
        val completions = SeriesCompletions()
        completions.record(1)
        completions.record(2)

        completions.retainOnly(setOf(2L))

        completions.counts.value shouldBe mapOf(2L to 1)
    }

    @Test
    @DisplayName("clearing forgets every series")
    fun clearForgetsAll() {
        val completions = SeriesCompletions()
        completions.record(1)

        completions.clear()

        completions.counts.value shouldBe emptyMap()
    }
}
