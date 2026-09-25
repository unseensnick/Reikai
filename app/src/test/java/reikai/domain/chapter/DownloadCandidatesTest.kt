package reikai.domain.chapter

import eu.kanade.presentation.manga.DownloadAction
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

    @Test
    fun `next N skips read and already downloaded or queued chapters before taking N`() {
        val readingOrder = (1..8).toList()

        DownloadCandidates.forAction(
            readingOrder,
            DownloadAction.NEXT_5_CHAPTERS,
            isRead = { it == 1 },
            isBookmarked = { false },
            isExcluded = { it == 2 || it == 4 },
        ) shouldBe listOf(3, 5, 6, 7, 8)
    }

    @Test
    fun `bookmarked takes bookmarked chapters read or not, minus the excluded`() {
        DownloadCandidates.forAction(
            listOf(1, 2, 3),
            DownloadAction.BOOKMARKED_CHAPTERS,
            isRead = { it == 1 },
            isBookmarked = { it != 3 },
            isExcluded = { it == 2 },
        ) shouldBe listOf(1)
    }
}
