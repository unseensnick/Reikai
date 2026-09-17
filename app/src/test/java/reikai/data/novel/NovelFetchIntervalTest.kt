package reikai.data.novel

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate

/** A novel's smart update prediction is stored from its own chapters, keeping an interval the user set. */
class NovelFetchIntervalTest {

    private val zone = TimeZone.UTC
    private val now = LocalDateTime(2026, 9, 17, 12, 0)
    private val stored = slot<NovelUpdate>()
    private val novels = mockk<NovelRepository> { coEvery { update(capture(stored)) } returns true }
    private val chapters = mockk<NovelChapterRepository>()

    @Test
    fun `chapters released every two days predict a two day interval`() = runTest {
        coEvery { chapters.getByNovelId(1L) } returns
            listOf(uploadedOn(17), uploadedOn(15), uploadedOn(13), uploadedOn(11))

        updateNovelFetchInterval(novel(), chapters, novels, zone = zone, now = now)

        stored.captured.fetchInterval shouldBe 2
    }

    @Test
    fun `an interval the user set is kept`() = runTest {
        updateNovelFetchInterval(novel().copy(fetchInterval = -5), chapters, novels, zone = zone, now = now)

        stored.captured.fetchInterval shouldBe -5
    }

    private fun novel() = Novel.create().copy(id = 1L)

    private fun uploadedOn(day: Int) = NovelChapter(
        id = day.toLong(),
        novelId = 1L,
        url = "/c$day",
        name = "Chapter $day",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = day.toDouble(),
        sourceOrder = 0L,
        dateFetch = 0L,
        dateUpload = LocalDateTime(2026, 9, day, 8, 0).toInstant(zone).toEpochMilliseconds(),
        page = "",
    )
}
