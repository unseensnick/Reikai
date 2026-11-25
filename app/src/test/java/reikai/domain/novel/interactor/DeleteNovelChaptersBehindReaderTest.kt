package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.GetNovelCategories
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.download.NovelRemovableDownloads
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelChapter
import reikai.domain.reader.ChapterIncognito
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadPendingDeleter
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import kotlin.time.Instant

/**
 * "After reading automatically delete" in the novel reader, as Mihon's reader runs it: finishing a
 * chapter queues the one the slots retire, and leaving the reader deletes what was queued. Which chapter
 * that is, is the shared kernel's rule, pinned in DeleteBehindReaderTest.
 */
class DeleteNovelChaptersBehindReaderTest {

    private val order = listOf(1L, 2L, 3L, 4L, 5L)
    private val publicSource = ChapterIncognito(sourceOf = { null }, isIncognito = { false })

    private fun chapter(id: Long, read: Boolean = true) = NovelChapter(
        id = id,
        novelId = 7L,
        url = "c$id",
        name = "Chapter $id",
        read = read,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = Instant.fromEpochMilliseconds(0L),
        dateUpload = null,
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
        excludedCategoryIds: Set<String> = emptySet(),
        novelCategoryIds: List<Long> = emptyList(),
    ): DeleteNovelChaptersBehindReader {
        // Seeded rather than set(): InMemoryPreferenceStore holds an immutable map, so a set() never
        // reaches the next read.
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("novel_remove_after_read_slots", slots, -1),
                InMemoryPreferenceStore.InMemoryPreference(
                    "novel_remove_exclude_categories",
                    excludedCategoryIds,
                    emptySet<String>(),
                ),
            ),
        )
        val repo = mockk<NovelChapterRepository>()
        coEvery { repo.getById(any()) } answers { chapters[firstArg<Long>()] }
        coEvery { repo.getByNovelId(any()) } returns chapters.values.toList()
        val categories = mockk<GetNovelCategories>()
        coEvery { categories.awaitByNovelId(any()) } returns novelCategoryIds.map {
            Category(id = it, name = "c$it", order = it, flags = 0L)
        }
        val novelPreferences = NovelPreferences(store)
        return DeleteNovelChaptersBehindReader(
            novelPreferences = novelPreferences,
            removableDownloads = NovelRemovableDownloads(novelPreferences, categories),
            downloadManager = {
                managerBuilt = true
                manager
            },
            chapterRepository = repo,
            pendingDeleter = pending,
            libraryPreferences = LibraryPreferences(store),
            copyMarks = InMemoryChapterCopyMarks(),
        )
    }

    @Test
    fun `finishing a chapter queues the one the slots retire`() = runTest {
        subject(
            slots = 1,
        ).await(
            orderedIds = order,
            readChapterId = 4L,
            memberIds = listOf(7L),
            stitch = emptyList(),
            incognito = publicSource,
        )

        queued.map { it.id } shouldBe listOf(3L)
    }

    @Test
    fun `finishing a chapter deletes nothing while the reader is open`() = runTest {
        subject(
            slots = 0,
        ).await(
            orderedIds = order,
            readChapterId = 4L,
            memberIds = listOf(7L),
            stitch = emptyList(),
            incognito = publicSource,
        )

        coVerify(exactly = 0) { manager.deleteChapters(any()) }
    }

    @Test
    fun `leaving the reader deletes the queued chapters`() = runTest {
        val interactor = subject(slots = 1)
        interactor.await(
            orderedIds = order,
            readChapterId = 4L,
            memberIds = listOf(7L),
            stitch = emptyList(),
            incognito = publicSource,
        )

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
        subject(
            chapters = chapters,
        ).await(
            orderedIds = order,
            readChapterId = 4L,
            memberIds = listOf(7L),
            stitch = emptyList(),
            incognito = publicSource,
        )

        queued shouldBe emptyList()
    }

    @Test
    fun `a novel in an excluded category keeps its chapters`() = runTest {
        subject(excludedCategoryIds = setOf("11"), novelCategoryIds = listOf(11L))
            .await(
                orderedIds = order,
                readChapterId = 4L,
                memberIds = listOf(7L),
                stitch = emptyList(),
                incognito = publicSource,
            )

        queued shouldBe emptyList()
    }
}
