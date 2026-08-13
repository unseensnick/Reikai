package reikai.presentation.recents

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

/**
 * The show-read rule, parameterized over both content types rather than written twice: it is one rule
 * about what a combined feed suggests next, and a per-type copy is what the content layer exists to
 * stop. [ContentType.ALL] is excluded because it names no entry id space.
 */
class RecentsRowGateTest {

    private fun entry(type: ContentType): EntryId = when (type) {
        ContentType.MANGA -> EntryId.Manga(1)
        ContentType.NOVELS -> EntryId.Novel(1)
        ContentType.ALL -> error("not an id space")
    }

    private fun gate(showRead: Boolean, unread: Set<EntryId>) =
        RecentsRowGate(RecentsChapterFilters.NONE, showRead = showRead, unread = unread)

    private fun read(entry: EntryId) =
        RecentsItem(entry, timestamp = 0, lane = RecentsLane.Read(ChapterRef(entry, 1)), payload = Unit)

    private fun updated(entry: EntryId) =
        RecentsItem(entry, timestamp = 0, lane = RecentsLane.Updated(ChapterRef(entry, 1)), payload = Unit)

    private fun added(entry: EntryId) =
        RecentsItem(entry, timestamp = 0, lane = RecentsLane.Added, payload = Unit)

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a caught-up read row is dropped from a combined mode`(type: ContentType) {
        val entry = entry(type)

        gate(showRead = false, unread = emptySet()).keeps(read(entry), RecentsMode.FEED) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a caught-up updated row is dropped from a combined mode`(type: ContentType) {
        val entry = entry(type)

        gate(showRead = false, unread = emptySet()).keeps(updated(entry), RecentsMode.DIGEST) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a row with something left to read stays`(type: ContentType) {
        val entry = entry(type)

        gate(showRead = false, unread = setOf(entry)).keeps(read(entry), RecentsMode.FEED) shouldBe true
    }

    /**
     * The single-lane modes are a record of what happened, so hiding a caught-up series there would
     * hide the very event the tab exists to report.
     */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a caught-up row is left alone outside the combined modes`(type: ContentType) {
        val entry = entry(type)
        val caughtUp = gate(showRead = false, unread = emptySet())

        (
            caughtUp.keeps(read(entry), RecentsMode.HISTORY) to
                caughtUp.keeps(updated(entry), RecentsMode.UPDATES)
            ) shouldBe (true to true)
    }

    /** A newly added row names no chapter, so "read" says nothing about it either way. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a newly added row is never dropped for being read`(type: ContentType) {
        val entry = entry(type)

        gate(showRead = false, unread = emptySet()).keeps(added(entry), RecentsMode.FEED) shouldBe true
    }

    @Test
    fun `turning the filter off keeps a caught-up row`() {
        val entry = EntryId.Manga(1)

        gate(showRead = true, unread = emptySet()).keeps(read(entry), RecentsMode.FEED) shouldBe true
    }
}
