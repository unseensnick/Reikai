package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A chapter folder holding pictures and an archive lists as one chapter before volumes existed, and as a volume
 * now: an Extras chapter at the folder's address plus a slash, beside the archive. Numbers as LocalSource gives
 * them: an archive named for the chapter keeps its number, and the Extras chapter takes one just below it.
 */
class TombstoneRevivalsTest {

    @Test
    fun `a chapter folder now read as a volume revives its state onto its Extras chapter`() {
        tombstoneRevivals(
            arriving = listOf(
                SyncedChapter(0, "s/ch1/", 0.99),
                SyncedChapter(0, "s/ch1/ch1.cbz", 1.0),
            ),
            tombstoned = emptyList(),
            removed = listOf(SyncedChapter(1, "s/ch1", 1.0)),
        ) shouldBe listOf(Revival("s/ch1/", "s/ch1"))
    }
}
