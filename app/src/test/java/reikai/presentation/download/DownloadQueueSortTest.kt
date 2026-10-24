package reikai.presentation.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Instant

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

    @Test
    fun `upload date orders by the upload instant`() {
        DownloadQueueSortKey.UPLOAD_DATE.sortValue(Instant.fromEpochMilliseconds(5_000L), chapterNumber = 2.0) shouldBe
            5_000.0
    }

    @Test
    fun `chapter number orders by the number`() {
        DownloadQueueSortKey.CHAPTER_NUMBER.sortValue(
            Instant.fromEpochMilliseconds(5_000L),
            chapterNumber = 2.0,
        ) shouldBe
            2.0
    }

    @Test
    fun `an undated chapter has no upload-date value`() {
        DownloadQueueSortKey.UPLOAD_DATE.sortValue(dateUpload = null, chapterNumber = 2.0) shouldBe null
    }
}
