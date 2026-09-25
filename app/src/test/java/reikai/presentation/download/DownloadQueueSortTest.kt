package reikai.presentation.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The queue sort is a one-shot reorder, so the sheet opens with no key active and a tap never guesses. */
class DownloadQueueSortTest {

    @Test
    fun `a first tap sorts ascending`() {
        null.next(DownloadQueueSortKey.CHAPTER_NUMBER) shouldBe
            DownloadQueueSort(DownloadQueueSortKey.CHAPTER_NUMBER, descending = false)
    }

    @Test
    fun `tapping the active key flips its direction`() {
        DownloadQueueSort(DownloadQueueSortKey.CHAPTER_NUMBER, descending = false)
            .next(DownloadQueueSortKey.CHAPTER_NUMBER) shouldBe
            DownloadQueueSort(DownloadQueueSortKey.CHAPTER_NUMBER, descending = true)
    }

    @Test
    fun `tapping the other key sorts it ascending`() {
        DownloadQueueSort(DownloadQueueSortKey.CHAPTER_NUMBER, descending = true)
            .next(DownloadQueueSortKey.UPLOAD_DATE) shouldBe
            DownloadQueueSort(DownloadQueueSortKey.UPLOAD_DATE, descending = false)
    }
}
