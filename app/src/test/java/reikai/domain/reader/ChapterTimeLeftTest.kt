package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.readinglog.ReadingPace
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ChapterTimeLeftTest {

    private val tenSecondsAPage = ChapterPace(units = 30, pace = ReadingPace.Known(10_000.0))

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
        val novel = ChapterPace(units = 2_000, pace = ReadingPace.Known(250.0))

        chapterTimeLeft(novel, ChapterProgress.Percent(7_500)) shouldBe ReaderTimeLeft.Remaining(125.seconds)
    }

    @Test
    fun `an unknown pace is still learning`() {
        val learning = ChapterPace(units = 30, pace = ReadingPace.Learning)

        chapterTimeLeft(learning, ChapterProgress.Pages(0, 30)) shouldBe ReaderTimeLeft.Learning
    }

    @Test
    fun `no position shows nothing`() {
        chapterTimeLeft(tenSecondsAPage, null) shouldBe null
    }

    @Test
    fun `a chapter whose pages have not loaded shows nothing`() {
        chapterTimeLeft(tenSecondsAPage, ChapterProgress.Pages(0, 0)) shouldBe null
    }
}
