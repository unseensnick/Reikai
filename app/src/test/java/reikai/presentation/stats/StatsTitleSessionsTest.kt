package reikai.presentation.stats

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.readinglog.EntryReadingSession
import kotlin.time.Instant

class StatsTitleSessionsTest {

    private val endedAt = Instant.parse("2026-10-16T12:00:00Z")

    @Test
    fun `a merged member's session counts under its series by the series' name`() {
        val session = EntryReadingSession(entryId = 7L, title = "Member", endedAt = endedAt, duration = 1_000L)

        val mapped = titleSessions(listOf(session), mapOf(7L to 1L), listOf(title(1L, "Series")), EntryId::Manga)

        mapped.single().let { it.entryId to it.name } shouldBe (EntryId.Manga(1L) to "Series")
    }

    @Test
    fun `a session on a series outside the library still counts, under its own entry and title`() {
        val session =
            EntryReadingSession(entryId = 9L, title = "Read from Browse", endedAt = endedAt, duration = 1_000L)

        val mapped = titleSessions(listOf(session), emptyMap(), emptyList(), EntryId::Novel)

        mapped.single().let { it.entryId to it.name } shouldBe (EntryId.Novel(9L) to "Read from Browse")
    }

    private fun title(id: Long, name: String) = StatsTitle(
        entryId = EntryId.Manga(id),
        title = name,
        status = 1L,
        sources = emptyList(),
        genres = emptyList(),
        categoryIds = emptyList(),
        totalChapters = 0,
        readChapters = 0,
        trackerIds = emptyList(),
        meanScore = null,
        trackStatus = null,
        readDuration = 0L,
        trackerStartedAt = null,
        firstLoggedReadAt = null,
    )
}
