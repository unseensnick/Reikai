package reikai.presentation.stats

import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.readinglog.EntryReadingSession
import kotlin.time.Instant

class StatsChartsTest {

    private val today = LocalDate(2026, 10, 16)

    @Test
    fun `the week ends on today and holds its reading`() {
        val sessions = listOf(session(Instant.parse("2026-10-16T12:00:00Z"), MINUTE * 5))

        charts(sessions).lastWeek.last() shouldBe (today to MINUTE * 5)
    }

    @Test
    fun `the week runs seven days, oldest first`() {
        charts(emptyList()).lastWeek.first().first shouldBe LocalDate(2026, 10, 10)
    }

    @Test
    fun `with nothing logged there is no logging start`() {
        charts(emptyList()).loggedSince shouldBe null
    }

    @Test
    fun `logging starts on the day the first session began`() {
        val sessions = listOf(session(Instant.parse("2026-10-03T00:10:00Z"), MINUTE * 30))

        charts(sessions).loggedSince shouldBe LocalDate(2026, 10, 2)
    }

    private fun charts(sessions: List<TitleReadingSession>) = statsCharts(
        listOf(title),
        sessions,
        emptyMap(),
        today,
        TimeZone.UTC,
    )

    private fun session(endedAt: Instant, duration: Long) =
        TitleReadingSession(title.entryId, title.title, EntryReadingSession(1L, "", endedAt, duration))

    private val title = StatsTitle(
        entryId = EntryId.Manga(1L),
        title = "",
        status = 1L,
        sources = emptyList(),
        genres = emptyList(),
        categoryIds = emptyList(),
        totalChapters = 0,
        readChapters = 0,
        trackerIds = emptyList(),
        meanScore = null,
        trackStatus = null,
        trackerStartedAt = null,
        readDuration = 0,
        firstLoggedReadAt = null,
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
