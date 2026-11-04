package reikai.presentation.stats

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.library.ContentType
import reikai.domain.readinglog.ReadingPace

class StatsPaceTest {

    @ParameterizedTest
    @CsvSource(
        "MANGA, 3600.0, 4",
        "MANGA, 1000.0, 1",
        "NOVELS, 250.0, 240",
        "NOVELS, 2000.0, 30",
    )
    fun `a pace reads as seconds a page or words a minute`(type: ContentType, msPerUnit: Double, measure: Long) {
        StatsPace(type, ReadingPace.Known(msPerUnit)).measure() shouldBe measure
    }

    @Test
    fun `a pace still learning has no measure`() {
        StatsPace(ContentType.NOVELS, ReadingPace.Learning).measure() shouldBe null
    }

    @Test
    fun `each type shown has its own pace`() {
        val library = statsLibrary(mangaPace = ReadingPace.Known(4_000.0))

        library.paces(ContentType.ALL) shouldBe listOf(
            StatsPace(ContentType.MANGA, ReadingPace.Known(4_000.0)),
            StatsPace(ContentType.NOVELS, ReadingPace.Learning),
        )
    }

    @Test
    fun `a type not shown leaves its pace out`() {
        statsLibrary().paces(ContentType.NOVELS) shouldBe listOf(StatsPace(ContentType.NOVELS, ReadingPace.Learning))
    }
}
