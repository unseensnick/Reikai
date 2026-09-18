package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What a chapter a sync adds arrives as, for manga and novels alike. */
class ChapterArrivalsTest {

    private fun arrive(
        added: ArrivingChapter,
        stored: List<StoredChapter> = emptyList(),
        removed: List<StoredChapter> = emptyList(),
        markDuplicateAsRead: Boolean = true,
    ) = chapterArrivals(listOf(added), stored, removed, markDuplicateAsRead, now = NOW).single()

    @Test
    fun `a new chapter numbered like a read one arrives read and held back as a duplicate`() {
        val arrival = arrive(ArrivingChapter(5.0), stored = listOf(StoredChapter(5.0, read = true)))

        (arrival.read to arrival.isChangedOrDuplicate) shouldBe (true to true)
    }

    @Test
    fun `a new chapter numbered like a read one stays unread with the setting off`() {
        arrive(ArrivingChapter(5.0), stored = listOf(StoredChapter(5.0, read = true)), markDuplicateAsRead = false)
            .read shouldBe false
    }

    @Test
    fun `an unnumbered read chapter does not mark a new unnumbered chapter read`() {
        arrive(ArrivingChapter(-1.0), stored = listOf(StoredChapter(-1.0, read = true))).read shouldBe false
    }

    @Test
    fun `a re-added chapter takes its removed twin's read, bookmark and earliest fetch date`() {
        val removed = listOf(
            StoredChapter(5.0, read = true, bookmark = true, dateFetch = 3_000L),
            StoredChapter(5.0, read = false, bookmark = false, dateFetch = 1_000L),
        )

        arrive(ArrivingChapter(5.0), removed = removed) shouldBe
            Arrival(read = true, bookmark = true, dateFetch = 1_000L, isChangedOrDuplicate = true)
    }

    @Test
    fun `an unnumbered chapter does not inherit an unnumbered removed chapter's state`() {
        val removed = listOf(StoredChapter(-1.0, read = true, bookmark = true, dateFetch = 1_000L))

        arrive(ArrivingChapter(-1.0), removed = removed) shouldBe
            Arrival(read = false, bookmark = false, dateFetch = NOW + 1, isChangedOrDuplicate = false)
    }

    @Test
    fun `a genuinely new chapter keeps its own state and is not held back`() {
        arrive(ArrivingChapter(1.0, read = false, bookmark = true)) shouldBe
            Arrival(read = false, bookmark = true, dateFetch = NOW + 1, isChangedOrDuplicate = false)
    }

    @Test
    fun `fetch dates count down in source order so the newest listed sorts first`() {
        val arrivals = chapterArrivals(
            listOf(ArrivingChapter(3.0), ArrivingChapter(2.0), ArrivingChapter(1.0)),
            stored = emptyList(),
            removed = emptyList(),
            markDuplicateAsRead = true,
            now = NOW,
        )

        arrivals.map { it.dateFetch } shouldBe listOf(NOW + 3, NOW + 2, NOW + 1)
    }

    private companion object {
        const val NOW = 10_000L
    }
}
