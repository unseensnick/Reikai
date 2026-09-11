package reikai.presentation.reader.text

import eu.kanade.domain.track.service.TrackPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * What finishing a chapter marks read in every novel reader. Novel 1 lists chapter 5 twice (5 and 55);
 * novel 2, merged with it, holds the stitched copy of it (205, numbered 4 on its own list) and a
 * chapter 5 of its own (206) that is a different chapter.
 */
class NovelChapterFinishTest {

    private fun chapter(id: Long, novelId: Long, number: Double) = NovelChapter(
        id = id, novelId = novelId, url = "/$id", name = "", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = number, sourceOrder = id, dateFetch = 0L,
        dateUpload = 0L, page = "",
    )

    private val finished = chapter(5L, novelId = 1L, number = 5.0)
    private val stitch = listOf(ChapterUnit(5L, 0, 0), ChapterUnit(205L, 0, 1), ChapterUnit(206L, 1, 1))
    private val repo = mockk<NovelChapterRepository>(relaxed = true).also {
        coEvery { it.getByNovelId(1L) } returns listOf(finished, chapter(55L, novelId = 1L, number = 5.0))
        coEvery { it.getByNovelId(2L) } returns
            listOf(chapter(205L, novelId = 2L, number = 4.0), chapter(206L, novelId = 2L, number = 5.0))
    }

    private fun subject(libraryStore: InMemoryPreferenceStore = InMemoryPreferenceStore()) = NovelChapterFinish(
        chapterRepo = repo,
        setNovelReadStatus = SetNovelReadStatus(repo, mockk(relaxed = true)),
        libraryPreferences = LibraryPreferences(libraryStore),
        trackPreferences = TrackPreferences(InMemoryPreferenceStore()),
        trackNovelChapter = mockk(relaxed = true),
        context = mockk(relaxed = true),
    )

    private suspend fun NovelChapterFinish.markedRead(): Set<Long> {
        finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}
        val ids = slot<List<Long>>()
        coVerify { repo.setReadBulk(capture(ids), true) }
        return ids.captured.toSet()
    }

    @Test
    fun `finishing a chapter marks its second listing and its stitched copy, not a sibling's same number`() =
        runTest {
            subject().markedRead() shouldBe setOf(5L, 55L, 205L)
        }

    @Test
    fun `with mark duplicate read off, finishing marks only the chapter`() = runTest {
        val off = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    "mark_duplicate_read_chapter_read",
                    emptySet<String>(),
                    emptySet(),
                ),
            ),
        )

        subject(off).markedRead() shouldBe setOf(5L)
    }

    @Test
    fun `a chapter finished again in the same session is not marked again`() = runTest {
        val finish = subject()

        finish.finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}
        finish.finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}

        coVerify(exactly = 1) { repo.setReadBulk(listOf(5L, 55L, 205L), true) }
    }
}
