package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NovelWordDensityTest {

    @ParameterizedTest
    @CsvSource("0,1", "400,1", "401,2", "500,2", "501,3", "1200,9", "1201,10", "50000,10")
    fun `the tier is 1 up to 400 words then one per 100`(average: Long, tier: Int) {
        NovelWordDensity.densityTier(average) shouldBe tier
    }

    @ParameterizedTest
    @CsvSource("1", "2", "5", "9", "10")
    fun `a tier's lowest average lands in it`(tier: Int) {
        NovelWordDensity.densityTier(NovelWordDensity.tierMinWords(tier)) shouldBe tier
    }

    @Test
    fun `the top tier has no ceiling`() {
        NovelWordDensity.tierMaxWords(NovelWordDensity.MAX_TIER) shouldBe null
    }

    @Test
    fun `the average divides by the chapters counted`() {
        NovelWordDensity(totalWords = 900, countedChapters = 2, totalChapters = 5).averageWords shouldBe 450
    }

    @Test
    fun `nothing counted averages zero`() {
        NovelWordDensity(totalWords = 0, countedChapters = 0, totalChapters = 5).averageWords shouldBe 0
    }

    @Test
    fun `unreadable chapters are kept apart from those not downloaded`() {
        NovelWordDensity(1500, countedChapters = 3, totalChapters = 10, unreadableChapters = 2)
            .notDownloadedChapters shouldBe 5
    }
}
