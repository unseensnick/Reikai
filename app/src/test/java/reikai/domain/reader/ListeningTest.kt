package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ListeningTest {

    private fun at(words: Long, paragraph: Int, chapterId: Long = 1) =
        ReadAloudProgress(chapterId, paragraph, wordsSpoken = words, words = 2_000)

    @Test
    fun `nothing is measured before enough words are spoken`() {
        val started = Listening().next(playing = true, at(0, paragraph = 0), nowMs = 0)

        started.next(playing = true, at(20, paragraph = 1), nowMs = 5_000).msPerWord shouldBe null
    }

    @Test
    fun `the live pace is the time since play over the words spoken since`() {
        val started = Listening().next(playing = true, at(100, paragraph = 1), nowMs = 0)

        started.next(playing = true, at(220, paragraph = 2), nowMs = 30_000).msPerWord shouldBe 250.0
    }

    @Test
    fun `the time between two paragraphs is listened time on the chapter being spoken`() {
        val started = Listening().next(playing = true, at(100, paragraph = 1, chapterId = 7), nowMs = 0)

        started.next(playing = true, at(220, paragraph = 2, chapterId = 7), nowMs = 30_000)
            .listened shouldBe (7L to 30_000L)
    }

    @Test
    fun `pausing credits the time up to the pause and ends the live pace`() {
        val playing = Listening()
            .next(playing = true, at(100, paragraph = 1), nowMs = 0)
            .next(playing = true, at(220, paragraph = 2), nowMs = 30_000)

        val paused = playing.next(playing = false, at(220, paragraph = 2), nowMs = 40_000)

        (paused.listened to paused.msPerWord) shouldBe ((1L to 10_000L) to null)
    }

    @Test
    fun `a new chapter keeps the voice's pace and starts timing again`() {
        val playing = Listening()
            .next(playing = true, at(100, paragraph = 1), nowMs = 0)
            .next(playing = true, at(220, paragraph = 2), nowMs = 30_000)

        val nextChapter = playing.next(playing = true, at(0, paragraph = 0, chapterId = 2), nowMs = 31_000)

        nextChapter.msPerWord shouldBe 250.0
    }

    @Test
    fun `stepping back restarts the timing`() {
        val playing = Listening()
            .next(playing = true, at(100, paragraph = 1), nowMs = 0)
            .next(playing = true, at(220, paragraph = 2), nowMs = 30_000)
            .next(playing = true, at(50, paragraph = 0), nowMs = 31_000)

        // Measured from the step back: 120 words in 60 seconds, where the kept pace was 250.
        playing.next(playing = true, at(170, paragraph = 1), nowMs = 91_000).msPerWord shouldBe 500.0
    }

    @Test
    fun `enough time with too few words measures nothing`() {
        val started = Listening().next(playing = true, at(0, paragraph = 0), nowMs = 0)

        // 40 words in 20 seconds is a pace a voice reads at, but too few words to trust.
        started.next(playing = true, at(40, paragraph = 1), nowMs = 20_000).msPerWord shouldBe null
    }

    @Test
    fun `enough words in too little time measures nothing`() {
        val started = Listening().next(playing = true, at(0, paragraph = 0), nowMs = 0)

        // 80 words in 8 seconds is 100 ms a word, inside the bounds, over too short a stretch.
        started.next(playing = true, at(80, paragraph = 1), nowMs = 8_000).msPerWord shouldBe null
    }

    @Test
    fun `a step longer than any listening credits only the cap`() {
        val started = Listening().next(playing = true, at(0, paragraph = 0), nowMs = 0)

        started.next(playing = true, at(100, paragraph = 1), nowMs = 2 * 60 * 60 * 1000L)
            .listened shouldBe (1L to 30 * 60 * 1000L)
    }

    @Test
    fun `skipping ahead restarts the timing`() {
        // 300 words at 400 ms a word, then six paragraphs of 50 words skipped in three seconds.
        val skipped = (1..6).fold(
            Listening()
                .next(playing = true, at(0, paragraph = 0), nowMs = 0)
                .next(playing = true, at(300, paragraph = 1), nowMs = 120_000),
        ) { state, step ->
            state.next(playing = true, at(300L + step * 50, paragraph = 1 + step), nowMs = 120_000L + step * 500)
        }

        // Measured from the skip: 100 words in 30 seconds, where the kept pace was 400.
        skipped.next(playing = true, at(700, paragraph = 8), nowMs = 153_000).msPerWord shouldBe 300.0
    }

    @Test
    fun `passing over paragraphs well into one restarts the timing`() {
        // Five 60-word paragraphs at 400 ms a word, then 20 seconds into the sixth a jump of six paragraphs.
        val spoken = (1..5).fold(Listening().next(playing = true, at(0, paragraph = 0), nowMs = 0)) { state, step ->
            state.next(playing = true, at(step * 60L, paragraph = step), nowMs = step * 24_000L)
        }
        val jumped = spoken.next(playing = true, at(660, paragraph = 11), nowMs = 140_000)

        // Measured from the jump: 60 words in 30 seconds, where counting the jump as speech gives about 212.
        jumped.next(playing = true, at(720, paragraph = 12), nowMs = 170_000).msPerWord shouldBe 500.0
    }

    @Test
    fun `a pace slower than any voice reads is ignored`() {
        // 60 words in three minutes is 3000 ms a word: a voice that stalled, not one reading.
        val started = Listening().next(playing = true, at(0, paragraph = 0), nowMs = 0)

        started.next(playing = true, at(60, paragraph = 1), nowMs = 180_000).msPerWord shouldBe null
    }
}
