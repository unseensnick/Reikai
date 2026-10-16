package reikai.presentation.stats

import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.readinglog.EntryReadingSession
import kotlin.time.Instant

/** The Statistics rules over [StatsTitle], which both content types are built into alike. */
class StatsKernelsTest {

    private val utc = TimeZone.UTC

    @Test
    fun `a session across midnight is split between the two days`() {
        val session = EntryReadingSession(1L, endedAt = Instant.parse("2026-10-02T00:10:00Z"), duration = 30 * MINUTE)

        readingTimeByDay(listOf(session), utc) shouldBe mapOf(
            LocalDate(2026, 10, 1) to 20 * MINUTE,
            LocalDate(2026, 10, 2) to 10 * MINUTE,
        )
    }

    @Test
    fun `a streak not yet read today still counts through yesterday`() {
        val days = setOf(LocalDate(2026, 10, 14), LocalDate(2026, 10, 15))

        readingStreaks(days, today = LocalDate(2026, 10, 16)).current shouldBe 2
    }

    @Test
    fun `a day missed before yesterday ends the current streak`() {
        val days = setOf(LocalDate(2026, 10, 13), LocalDate(2026, 10, 14))

        readingStreaks(days, today = LocalDate(2026, 10, 16)).current shouldBe 0
    }

    @Test
    fun `the longest streak is the longest run of days`() {
        val days = setOf(1, 2, 3, 5, 6).map { LocalDate(2026, 10, it) }.toSet()

        readingStreaks(days, today = LocalDate(2026, 10, 20)).longest shouldBe 3
    }

    @Test
    fun `a score under one still lands on the first bar`() {
        scoreBar(0.4) shouldBe 1
    }

    @Test
    fun `a tracker's start date wins over the reading log`() {
        title(
            trackerStartedAt = Instant.parse("2024-01-01T00:00:00Z"),
            firstLoggedReadAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
            .startedAt shouldBe Instant.parse("2024-01-01T00:00:00Z")
    }

    @Test
    fun `a series read from two sources counts under each`() {
        val sources = listOf(StatsSource("1", "One", "en"), StatsSource("2", "Two", "en"))

        listOf(title(sources = sources)).groupsBy(StatsDimension.SOURCE, utc).map { it.key } shouldBe
            listOf(StatsGroupKey.Source("1", "One"), StatsGroupKey.Source("2", "Two"))
    }

    @Test
    fun `tags that differ only in case are one row`() {
        listOf(title(genres = listOf("Action")), title(genres = listOf("action")))
            .groupsBy(StatsDimension.TAG, utc).map { it.key to it.count } shouldBe
            listOf(StatsGroupKey.Tag("Action") to 2)
    }

    @Test
    fun `an entry filed nowhere is in the Default category`() {
        listOf(title(categoryIds = emptyList())).groupsBy(StatsDimension.CATEGORY, utc).map { it.key } shouldBe
            listOf(StatsGroupKey.Category(0L))
    }

    @Test
    fun `a series with no chapters sits in the first length band`() {
        listOf(title(totalChapters = 0)).groupsBy(StatsDimension.LENGTH, utc).map { it.key } shouldBe
            listOf(StatsGroupKey.Length(0L..0L))
    }

    private fun title(
        sources: List<StatsSource> = listOf(StatsSource("1", "One", "en")),
        genres: List<String> = emptyList(),
        categoryIds: List<Long> = listOf(1L),
        totalChapters: Long = 3,
        trackerStartedAt: Instant? = null,
        firstLoggedReadAt: Instant? = null,
    ) = StatsTitle(
        entryId = EntryId.Manga(1L),
        status = 1L,
        sources = sources,
        genres = genres,
        categoryIds = categoryIds,
        totalChapters = totalChapters,
        readChapters = 0,
        trackerIds = emptyList(),
        meanScore = null,
        trackStatus = null,
        trackerStartedAt = trackerStartedAt,
        trackerFinishedAt = null,
        readDuration = 0,
        firstLoggedReadAt = firstLoggedReadAt,
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
