package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.readinglog.ReadingPace
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ChapterTimeLeftTest {

    private val tenSecondsAPage = ChapterPace(
        units = 30,
        reading = ReadingPace.Known(10_000.0),
        listening = ReadingPace.Learning,
    )

    @Test
    fun `the page on screen counts as unread`() {
        chapterTimeLeft(tenSecondsAPage, ChapterProgress.Pages(lastPageRead = 0, pageCount = 30)) shouldBe
            ReaderTimeLeft.Remaining(5.minutes)
    }

    @Test
    fun `the last page leaves one page to read`() {
        chapterTimeLeft(tenSecondsAPage, ChapterProgress.Pages(lastPageRead = 29, pageCount = 30)) shouldBe
            ReaderTimeLeft.Remaining(10.seconds)
    }

    @Test
    fun `a scrolled chapter leaves the share it has not reached`() {
        // 2,000 words at 250 ms each is 500 seconds, a quarter of which is left at 75%.
        val novel = ChapterPace(
            units = 2_000,
            reading = ReadingPace.Known(250.0),
            listening = ReadingPace.Learning,
        )

        chapterTimeLeft(novel, ChapterProgress.Percent(7_500)) shouldBe ReaderTimeLeft.Remaining(125.seconds)
    }

    @Test
    fun `an unknown pace is still learning`() {
        val learning = ChapterPace(
            units = 30,
            reading = ReadingPace.Learning,
            listening = ReadingPace.Known(250.0),
        )

        chapterTimeLeft(learning, ChapterProgress.Pages(0, 30)) shouldBe ReaderTimeLeft.Learning(listening = false)
    }

    @Test
    fun `no position shows nothing`() {
        chapterTimeLeft(tenSecondsAPage, null) shouldBe null
    }

    @Test
    fun `a chapter whose pages have not loaded shows nothing`() {
        chapterTimeLeft(tenSecondsAPage, ChapterProgress.Pages(0, 0)) shouldBe null
    }

    @Test
    fun `listening counts the words read aloud has still to speak at the live pace`() {
        val progress = ReadAloudProgress(chapterId = 1, wordsSpoken = 1_500, words = 2_000)

        listeningTimeLeft(progress, live = 240.0, learned = ReadingPace.Known(500.0)) shouldBe
            ReaderTimeLeft.Remaining(120.seconds)
    }

    @Test
    fun `listening falls back to the learned listening pace before the live one is measured`() {
        val progress = ReadAloudProgress(chapterId = 1, wordsSpoken = 1_500, words = 2_000)

        listeningTimeLeft(progress, live = null, learned = ReadingPace.Known(500.0)) shouldBe
            ReaderTimeLeft.Remaining(250.seconds)
    }

    @Test
    fun `listening with neither pace is still learning to listen`() {
        val progress = ReadAloudProgress(chapterId = 1, wordsSpoken = 0, words = 2_000)

        listeningTimeLeft(progress, live = null, learned = ReadingPace.Learning) shouldBe
            ReaderTimeLeft.Learning(listening = true)
    }
}
