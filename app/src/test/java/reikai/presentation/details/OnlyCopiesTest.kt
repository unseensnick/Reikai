package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The details list's Only copies view, which keeps each set of copies together. */
class OnlyCopiesTest {

    /** A series numbering each volume from 1, from two groups: two sets that share a chapter number. */
    @Test
    fun `two sets sharing a chapter number stay apart`() {
        val volumeOne = listOf(1L, 3L)
        val volumeTwo = listOf(2L, 4L)
        val state = loadedDetails(
            items = (1L..4L).map { detailsChapter(it, "Ch. 1 ($it)", 1.0) },
            duplicates = DuplicateMarks(
                copies = volumeOne.associateWith { volumeOne } + volumeTwo.associateWith { volumeTwo },
                marks = emptyMap(),
            ),
        )

        state.withOnlyCopies().chapters.items.map { (it as EntryChapterListItem.Chapter).id } shouldBe
            listOf(1L, 3L, 2L, 4L)
    }
}
