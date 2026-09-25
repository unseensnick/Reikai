package reikai.domain.chapter

import eu.kanade.presentation.manga.DownloadAction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class DownloadCandidatesTest {

    private val shown = listOf("b", "c")
    private val stored = listOf("a", "b", "c", "d")

    @Test
    fun `skipping filtered chapters picks from the rows on screen`() {
        DownloadCandidates.rows(shown, stored, skipFiltered = true) shouldBe shown
    }

    @Test
    fun `not skipping filtered chapters picks from every stored row`() {
        DownloadCandidates.rows(shown, stored, skipFiltered = false) shouldBe stored
    }

    /** Every caller, manga and novel, details toolbar and library, picks through this one rule. */
    @ParameterizedTest
    @EnumSource(DownloadAction::class)
    fun `a hidden chapter is never queued, whatever the action`(action: DownloadAction) {
        DownloadCandidates.forAction(
            listOf(1, 2, 3),
            action,
            isRead = { false },
            isBookmarked = { true },
            isHidden = { it == 1 },
            isExcluded = { false },
        ).first() shouldBe 2
    }

    @Test
    fun `next N skips read and already downloaded or queued chapters before taking N`() {
        val readingOrder = (1..8).toList()

        DownloadCandidates.forAction(
            readingOrder,
            DownloadAction.NEXT_5_CHAPTERS,
            isRead = { it == 1 },
            isBookmarked = { false },
            isHidden = { false },
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
            isHidden = { false },
            isExcluded = { it == 2 },
        ) shouldBe listOf(1)
    }
}
