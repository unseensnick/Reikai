package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ListeningTest {

    private fun at(words: Long, chapterId: Long = 1) = ReadAloudProgress(chapterId, wordsSpoken = words, words = 2_000)

    @Test
    fun `nothing is measured before enough words are spoken`() {
        val started = Listening().next(playing = true, at(0), nowMs = 0)

        started.next(playing = true, at(20), nowMs = 5_000).msPerWord shouldBe null
    }

    @Test
    fun `the live pace is the time since play over the words spoken since`() {
        val started = Listening().next(playing = true, at(100), nowMs = 0)

        started.next(playing = true, at(220), nowMs = 30_000).msPerWord shouldBe 250.0
    }

    @Test
    fun `the time between two paragraphs is listened time on the chapter being spoken`() {
        val started = Listening().next(playing = true, at(100, chapterId = 7), nowMs = 0)

        started.next(playing = true, at(220, chapterId = 7), nowMs = 30_000).listened shouldBe (7L to 30_000L)
    }

    @Test
    fun `pausing credits the time up to the pause and ends the live pace`() {
        val playing = Listening()
            .next(playing = true, at(100), nowMs = 0)
            .next(playing = true, at(220), nowMs = 30_000)

        val paused = playing.next(playing = false, at(220), nowMs = 40_000)

        (paused.listened to paused.msPerWord) shouldBe ((1L to 10_000L) to null)
    }

    @Test
    fun `a new chapter keeps the voice's pace and starts timing again`() {
        val playing = Listening()
            .next(playing = true, at(100), nowMs = 0)
            .next(playing = true, at(220), nowMs = 30_000)

        val nextChapter = playing.next(playing = true, at(0, chapterId = 2), nowMs = 31_000)

        nextChapter.msPerWord shouldBe 250.0
    }

    @Test
    fun `stepping back restarts the timing`() {
        val playing = Listening()
            .next(playing = true, at(100), nowMs = 0)
            .next(playing = true, at(220), nowMs = 30_000)
            .next(playing = true, at(50), nowMs = 31_000)

        // Measured from the step back: 120 words in 60 seconds, where the kept pace was 250.
        playing.next(playing = true, at(170), nowMs = 91_000).msPerWord shouldBe 500.0
    }

    @Test
    fun `a pace no voice reads at is ignored`() {
        // Skipping ahead a few paragraphs: 400 words in 12 seconds is 30 ms a word.
        val started = Listening().next(playing = true, at(100), nowMs = 0)

        started.next(playing = true, at(500), nowMs = 12_000).msPerWord shouldBe null
    }
}
