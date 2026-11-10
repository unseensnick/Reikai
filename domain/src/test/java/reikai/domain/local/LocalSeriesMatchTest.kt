package reikai.domain.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Which folders a renamed local series may have become: the ones holding every chapter file its rows name. */
class LocalSeriesMatchTest {

    private val paths = listOf("Vol 1/Ch 1", "Vol 1/Ch 2", "Vol 2/")

    @Test
    fun `a folder holding every chapter file the rows name is a candidate, even with more in it`() {
        val folders = mapOf(
            "Renamed" to listOf("Vol 1/", "Vol 1/Ch 1", "Vol 1/Ch 2", "Vol 2/", "Vol 2/Ch 3", "cover.jpg"),
            "Other" to listOf("Vol 1/", "Vol 1/Ch 1"),
        )

        relinkCandidates(paths, folders) shouldBe listOf("Renamed")
    }

    @Test
    fun `names are matched as the disk finds them, case-blind`() {
        val folders = mapOf("renamed" to listOf("vol 1/ch 1", "VOL 1/CH 2", "vol 2/"))

        relinkCandidates(paths, folders) shouldBe listOf("renamed")
    }

    @Test
    fun `two folders holding the files are both candidates, in name order`() {
        val folders = mapOf("Second" to paths, "First copy" to paths)

        relinkCandidates(paths, folders) shouldBe listOf("First copy", "Second")
    }

    @Test
    fun `a series with no chapter rows matches nothing`() {
        relinkCandidates(emptyList(), mapOf("Any" to listOf("Ch 1"))) shouldBe emptyList()
    }
}
