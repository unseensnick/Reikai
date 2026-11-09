package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ChapterVolumesTest {

    private data class Ch(val id: Int, val volume: String?)

    @ParameterizedTest(name = "{0}")
    @CsvSource("Vol 02, 2.0", "Volume 3, 3.0", "v4, 4.0", "Spike Series Vol. 1.epub, 1.0")
    fun `a volume name states its number`(name: String, number: Double) {
        volumeNumberIn(name) shouldBe number
    }

    @Test
    fun `a name with no volume number states none`() {
        volumeNumberIn("Extras") shouldBe null
    }

    @Test
    fun `chapters run under their volumes in list order`() {
        listOf(Ch(1, "a"), Ch(2, "a"), Ch(3, "b")).volumeRuns {
            it.volume
        }?.map { (v, c) -> v to c.map { it.id } } shouldBe
            listOf("a" to listOf(1, 2), "b" to listOf(3))
    }

    @Test
    fun `a volume the sort has parted heads nothing`() {
        listOf(Ch(1, "a"), Ch(2, "b"), Ch(3, "a")).volumeRuns { it.volume } shouldBe null
    }

    @Test
    fun `one volume heads nothing`() {
        listOf(Ch(1, "a"), Ch(2, "a")).volumeRuns { it.volume } shouldBe null
    }

    @Test
    fun `a chapter with no volume means none head the list`() {
        listOf(Ch(1, "a"), Ch(2, null), Ch(3, "b")).volumeRuns { it.volume } shouldBe null
    }
}
