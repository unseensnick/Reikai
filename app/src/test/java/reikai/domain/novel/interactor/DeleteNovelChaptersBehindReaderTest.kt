package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadPendingDeleter
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.model.Category

/**
 * "After reading automatically delete" in the novel reader, as Mihon's reader runs it: finishing a
 * chapter queues the one the slots retire, and leaving the reader deletes what was queued. Which chapter
 * that is, is the shared kernel's rule, pinned in DeleteBehindReaderTest.
 */
class DeleteNovelChaptersBehindReaderTest {

    private val order = listOf(1L, 2L, 3L, 4L, 5L)

    private fun chapter(id: Long, read: Boolean = true, bookmark: Boolean = false) = NovelChapter(
        id = id,
        novelId = 7L,
        url = "c$id",
        name = "Chapter $id",
        read = read,
        bookmark = bookmark,
        lastTextProgress = 0L,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    private val queued = mutableListOf<NovelChapter>()
    private val pending = mockk<NovelDownloadPendingDeleter> {
        every { addChapters(any()) } answers { queued += firstArg<List<NovelChapter>>() }
        every { takePendingChapterIds() } answers { queued.map { it.id }.also { queued.clear() } }
    }
    private val manager = mockk<NovelDownloadManager>(relaxed = true)
    private var managerBuilt = false

    private fun subject(
        slots: Int = 1,
        chapters: Map<Long, NovelChapter> = order.associateWith { chapter(it) },
        allowRemovingBookmarked: Boolean = false,
        excludedCategoryIds: Set<String> = emptySet(),
        novelCategoryIds: List<Long> = emptyList(),
    ): DeleteNovelChaptersBehindReader {
        // Seeded rather than set(): InMemoryPreferenceStore holds an immutable map, so a set() never
        // reaches the next read.
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("novel_remove_after_read_slots", slots, -1),
                InMemoryPreferenceStore.InMemoryPreference("novel_remove_bookmarked", allowRemovingBookmarked, false),
                InMemoryPreferenceStore.InMemoryPreference(
                    "novel_remove_exclude_categories",
                    excludedCategoryIds,
                    emptySet<String>(),
                ),
            ),
        )
        val repo = mockk<NovelChapterRepository>()
        coEvery { repo.getById(any()) } answers { chapters[firstArg<Long>()] }
        val categories = mockk<GetNovelCategories>()
        coEvery { categories.awaitByNovelId(any()) } returns novelCategoryIds.map {
            Category(id = it, name = "c$it", order = it, flags = 0L)
        }
        return DeleteNovelChaptersBehindReader(
            novelPreferences = NovelPreferences(store),
            getNovelCategories = categories,
            downloadManager = {
                managerBuilt = true
                manager
            },
            chapterRepository = repo,
            pendingDeleter = pending,
        )
    }

    @Test
    fun `finishing a chapter queues the one the slots retire`() = runTest {
        subject(slots = 1).await(novelId = 7L, orderedIds = order, readChapterId = 4L)

        queued.map { it.id } shouldBe listOf(3L)
    }

    @Test
    fun `finishing a chapter deletes nothing while the reader is open`() = runTest {
        subject(slots = 0).await(novelId = 7L, orderedIds = order, readChapterId = 4L)

        coVerify(exactly = 0) { manager.deleteChapters(any()) }
    }

    @Test
    fun `leaving the reader deletes the queued chapters`() = runTest {
        val interactor = subject(slots = 1)
        interactor.await(novelId = 7L, orderedIds = order, readChapterId = 4L)

        interactor.deletePending()

        coVerify { manager.deleteChapters(listOf(chapter(3L))) }
    }

    /** Building the manager resumes the persisted download queue, which closing a reader must not do. */
    @Test
    fun `leaving the reader with nothing queued leaves the download manager alone`() = runTest {
        subject().deletePending()

        managerBuilt shouldBe false
    }

    @Test
    fun `a chapter that is not read yet is not queued`() = runTest {
        val chapters = order.associateWith { chapter(it, read = it != 3L) }
        subject(chapters = chapters).await(novelId = 7L, orderedIds = order, readChapterId = 4L)

        queued shouldBe emptyList()
    }

    @Test
    fun `a bookmarked chapter is kept by default`() = runTest {
        val chapters = order.associateWith { chapter(it, bookmark = it == 3L) }
        subject(chapters = chapters).await(novelId = 7L, orderedIds = order, readChapterId = 4L)

        queued shouldBe emptyList()
    }

    @Test
    fun `a bookmarked chapter is queued once the user allows removing them`() = runTest {
        val chapters = order.associateWith { chapter(it, bookmark = it == 3L) }
        subject(chapters = chapters, allowRemovingBookmarked = true)
            .await(novelId = 7L, orderedIds = order, readChapterId = 4L)

        queued.map { it.id } shouldBe listOf(3L)
    }

    @Test
    fun `a novel in an excluded category keeps its chapters`() = runTest {
        subject(excludedCategoryIds = setOf("11"), novelCategoryIds = listOf(11L))
            .await(novelId = 7L, orderedIds = order, readChapterId = 4L)

        queued shouldBe emptyList()
    }

    /** The other half: a set with something in it must not stop every novel, only its members. */
    @Test
    fun `a novel outside the excluded categories is still queued`() = runTest {
        subject(excludedCategoryIds = setOf("11"), novelCategoryIds = listOf(12L))
            .await(novelId = 7L, orderedIds = order, readChapterId = 4L)

        queued.map { it.id } shouldBe listOf(3L)
    }
}
