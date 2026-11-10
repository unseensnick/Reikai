package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** What a chapter a sync adds arrives as, for manga and novels alike. */
class ChapterArrivalsTest {

    private fun arrive(
        added: ArrivingChapter,
        stored: List<StoredChapter> = emptyList(),
        removed: List<StoredChapter> = emptyList(),
        markDuplicateAsRead: Boolean = true,
    ) = chapterArrivals(listOf(added), stored, removed, markDuplicateAsRead, now = NOW).single()

    @Test
    fun `an arriving interlude numbered like a read chapter does not arrive read`() {
        val interlude = ArrivingChapter(5.0, name = "Interlude 5")

        arrive(interlude, stored = listOf(StoredChapter(5.0, read = true, name = "Chapter 5"))).read shouldBe false
    }

    @Test
    fun `a new chapter numbered like a read one arrives read and held back as a duplicate`() {
        val arrival = arrive(ArrivingChapter(5.0), stored = listOf(StoredChapter(5.0, read = true)))

        (arrival.read to arrival.isChangedOrDuplicate) shouldBe (true to true)
    }

    @Test
    fun `a new chapter the user marked Never skip stays unread beside a read copy`() {
        arrive(ArrivingChapter(5.0, mark = CopyMark.NeverSkip), stored = listOf(StoredChapter(5.0, read = true)))
            .read shouldBe false
    }

    @Test
    fun `a read chapter the user marked Never skip marks no copy of it read`() {
        arrive(ArrivingChapter(5.0), stored = listOf(StoredChapter(5.0, read = true, mark = CopyMark.NeverSkip)))
            .read shouldBe false
    }

    @Test
    fun `a new chapter grouped by hand with a read one arrives read whatever its number`() {
        val grouped = CopyMark.Group(1L)

        arrive(
            ArrivingChapter(7.0, name = "Side A", mark = grouped),
            stored = listOf(StoredChapter(3.0, read = true, name = "Other", mark = grouped)),
        ).read shouldBe true
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
            StoredChapter(5.0, read = true, bookmark = true, dateFetch = at(3_000L)),
            StoredChapter(5.0, read = false, bookmark = false, dateFetch = at(1_000L)),
        )

        arrive(ArrivingChapter(5.0), removed = removed) shouldBe
            Arrival(
                read = true,
                bookmark = true,
                dateFetch = at(1_000L),
                dateUpload = NOW,
                isChangedOrDuplicate = true,
                isRelisted = true,
            )
    }

    @Test
    fun `a first fetch finds no release`() {
        foundRelease(hadChapters = false, listOf(arrive(ArrivingChapter(1.0)))) shouldBe false
    }

    @Test
    fun `a new chapter on an entry that had chapters is a release`() {
        foundRelease(hadChapters = true, listOf(arrive(ArrivingChapter(6.0)))) shouldBe true
    }

    @Test
    fun `a chapter re-listed under a removed number is not a release`() {
        val relisted = arrive(ArrivingChapter(5.0), removed = listOf(StoredChapter(5.0, read = false)))

        foundRelease(hadChapters = true, listOf(relisted)) shouldBe false
    }

    @Test
    fun `a sync that added nothing finds no release`() {
        foundRelease(hadChapters = true, emptyList()) shouldBe false
    }

    @Test
    fun `an unnumbered chapter does not inherit an unnumbered removed chapter's state`() {
        val removed = listOf(StoredChapter(-1.0, read = true, bookmark = true, dateFetch = at(1_000L)))

        arrive(ArrivingChapter(-1.0), removed = removed) shouldBe
            Arrival(read = false, bookmark = false, dateFetch = NOW + 1.milliseconds, dateUpload = NOW, false, false)
    }

    @Test
    fun `a genuinely new chapter keeps its own state and is not held back`() {
        arrive(ArrivingChapter(1.0, read = false, bookmark = true)) shouldBe
            Arrival(read = false, bookmark = true, dateFetch = NOW + 1.milliseconds, dateUpload = NOW, false, false)
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

        arrivals.map { it.dateFetch } shouldBe listOf(NOW + 3.milliseconds, NOW + 2.milliseconds, NOW + 1.milliseconds)
    }

    @Test
    fun `an undated arrival takes the latest date listed before it, or now when none was`() {
        val arrivals = chapterArrivals(
            listOf(ArrivingChapter(4.0), ArrivingChapter(3.0, dateUpload = at(500L)), ArrivingChapter(2.0)),
            stored = emptyList(),
            removed = emptyList(),
            markDuplicateAsRead = true,
            now = NOW,
        )

        arrivals.map { it.dateUpload } shouldBe listOf(NOW, at(500L), at(500L))
    }

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)

    private companion object {
        val NOW = Instant.fromEpochMilliseconds(10_000L)
    }
}
