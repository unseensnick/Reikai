package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter

/**
 * The novel repositories log a failed write and report it instead of throwing, but the restore batch
 * only contains and logs an entry whose restore throws, so every write the restorer makes must be
 * checked.
 */
class NovelRestorerWriteFailureTest {

    class Repos {
        val novels = mockk<NovelRepository>(relaxed = true) {
            coEvery { getByUrlAndSource(any(), any()) } returns null
            coEvery { insert(any()) } returns NOVEL_ID
            coEvery { update(any<Novel>(), any()) } returns true
        }
        val chapters = mockk<NovelChapterRepository>(relaxed = true) {
            coEvery { getByNovelId(any()) } returns emptyList()
            coEvery { insert(any()) } returns 1L
            coEvery { update(any()) } returns true
        }
        val tracks = mockk<NovelTrackRepository>(relaxed = true) {
            coEvery { getTracksByNovelId(any()) } returns emptyList()
            coEvery { insert(any()) } returns true
        }
    }

    enum class FailingWrite(val fail: Repos.() -> Unit) {
        NOVEL_INSERT({ coEvery { novels.insert(any()) } returns null }),
        NOVEL_UPDATE({
            coEvery { novels.getByUrlAndSource(any(), any()) } returns
                Novel.create().copy(id = NOVEL_ID, url = URL, source = SOURCE)
            coEvery { novels.update(any<Novel>(), any()) } returns false
        }),
        CHAPTER_INSERT({ coEvery { chapters.insert(any()) } returns null }),
        CHAPTER_UPDATE({
            coEvery { chapters.getByNovelId(any()) } returns
                listOf(
                    NovelChapter(
                        id = 1L,
                        novelId = NOVEL_ID,
                        url = CHAPTER_URL,
                        name = "1",
                        read = false,
                        bookmark = false,
                        lastTextProgress = 0L,
                        chapterNumber = 1.0,
                        sourceOrder = 0L,
                        dateFetch = 0L,
                        dateUpload = 0L,
                        page = "",
                    ),
                )
            coEvery { chapters.update(any()) } returns false
        }),
        TRACK_INSERT({ coEvery { tracks.insert(any()) } returns false }),
    }

    @Test
    fun `a restore whose writes all succeed does not throw`() = runTest {
        restorer(Repos()).restore(backup, emptyList())
    }

    @ParameterizedTest
    @EnumSource(FailingWrite::class)
    fun `a failed write makes the restore throw`(write: FailingWrite) = runTest {
        val restorer = restorer(Repos().apply(write.fail))

        shouldThrow<IllegalStateException> { restorer.restore(backup, emptyList()) }
    }

    private fun restorer(repos: Repos) = NovelRestorer(
        novelRepository = repos.novels,
        novelChapterRepository = repos.chapters,
        categoryRepository = mockk(relaxed = true),
        novelTrackRepository = repos.tracks,
        restoreMergeGroups = mockk(relaxed = true),
        setCustomNovelInfo = mockk(relaxed = true),
        database = mockk(relaxed = true),
    )

    private val backup = BackupNovel(
        source = SOURCE,
        url = URL,
        chapters = listOf(BackupNovelChapter(url = CHAPTER_URL, name = "1", read = true)),
        tracking = listOf(BackupNovelTracking(trackerId = 1L)),
    )

    companion object {
        const val NOVEL_ID = 7L
        const val SOURCE = "src"
        const val URL = "/novel"
        const val CHAPTER_URL = "/novel/1"
    }
}
