package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadCandidatesTest {

    private val shown = listOf("b", "c")
    private val stored = listOf("a", "b", "c", "d")

    @Test
    fun `skipping filtered chapters picks from the rows on screen`() {
        DownloadCandidates.rows(shown, stored, skipFiltered = true) { false } shouldBe shown
    }

    @Test
    fun `not skipping filtered chapters picks from every stored row`() {
        DownloadCandidates.rows(shown, stored, skipFiltered = false) { false } shouldBe stored
    }

    @Test
    fun `a hidden chapter is never a candidate`() {
        DownloadCandidates.rows(shown, stored, skipFiltered = false) { it == "a" } shouldBe listOf("b", "c", "d")
    }
}
