package reikai.domain.chapter

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

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

    // A tombstone revives onto one arrival only, so a pair the case or number rung finds twice on either side
    // takes nothing: its state would otherwise land on whichever copy the write reaches first.
    @ParameterizedTest(name = "{0}")
    @MethodSource("ambiguousPairs")
    fun `a pair that is not unique on both sides revives nothing`(
        name: String,
        arriving: List<SyncedChapter>,
        removed: List<SyncedChapter>,
    ) {
        tombstoneRevivals(arriving, tombstoned = emptyList(), removed = removed).shouldBeEmpty()
    }

    private companion object {
        const val UNRECOGNIZED = -1.0

        @JvmStatic
        fun ambiguousPairs() = listOf(
            Arguments.of(
                "two arrivals share a number",
                listOf(SyncedChapter(0, "s/2.cbz", 2.0), SyncedChapter(0, "s/2 (alt).cbz", 2.0)),
                listOf(SyncedChapter(1, "s/old 2.cbz", 2.0)),
            ),
            Arguments.of(
                "two removed chapters share a number",
                listOf(SyncedChapter(0, "s/2.cbz", 2.0)),
                listOf(SyncedChapter(1, "s/old 2.cbz", 2.0), SyncedChapter(2, "s/older 2.cbz", 2.0)),
            ),
            Arguments.of(
                "two arrivals fold to one address",
                listOf(SyncedChapter(0, "s/Ch.cbz", UNRECOGNIZED), SyncedChapter(0, "s/CH.cbz", UNRECOGNIZED)),
                listOf(SyncedChapter(1, "s/ch.cbz", UNRECOGNIZED)),
            ),
            Arguments.of(
                "two removed chapters fold to one address",
                listOf(SyncedChapter(0, "s/ch.cbz", UNRECOGNIZED)),
                listOf(SyncedChapter(1, "s/Ch.cbz", UNRECOGNIZED), SyncedChapter(2, "s/CH.cbz", UNRECOGNIZED)),
            ),
        )
    }
}
