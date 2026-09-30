package reikai.domain.download

import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.tachiyomi.data.download.DownloadManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.DeleteNovelChaptersAfterRead
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * Marking a chapter read by hand with "delete after marked as read" on, through both content types'
 * real mark-read interactors. That is automatic removal, so each side filters through
 * [removableDownloads] before its manager's delete (DownloadManager.deleteRemovableChapters,
 * DeleteNovelChaptersAfterRead), and the halves record what reaches that delete. Nothing is on disk:
 * a chapter that is only queued reaches the delete too, which dequeues it.
 */
class MarkReadDeleteConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a chapter marked read in a category kept from removal keeps its download`(half: MarkReadHalf) = runTest {
        half.markRead(excluded = setOf("5"), categoryIds = listOf(5L)) shouldBe emptySet()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a chapter marked read outside the kept categories loses its download`(half: MarkReadHalf) = runTest {
        half.markRead(excluded = setOf("5"), categoryIds = listOf(6L)) shouldBe setOf(CHAPTER)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a bookmarked chapter marked read keeps its download`(half: MarkReadHalf) = runTest {
        half.markRead(excluded = emptySet(), categoryIds = listOf(6L), bookmarked = true) shouldBe emptySet()
    }

    companion object {
        const val ENTRY = 1L
        const val CHAPTER = 10L

        @JvmStatic
        fun halves() = listOf(MangaMarkReadHalf(), NovelMarkReadHalf())
    }
}

interface MarkReadHalf {
    /** Marks one unread chapter of an entry filed in [categoryIds] read; the ids whose download goes. */
    suspend fun markRead(excluded: Set<String>, categoryIds: List<Long>, bookmarked: Boolean = false): Set<Long>
}

class MangaMarkReadHalf : MarkReadHalf {
    override fun toString() = "manga"

    override suspend fun markRead(excluded: Set<String>, categoryIds: List<Long>, bookmarked: Boolean): Set<Long> {
        val preferences = DownloadPreferences(EmittingPreferenceStore())
        preferences.removeAfterMarkedAsRead.set(true)
        preferences.removeExcludeCategories.set(excluded)
        val manga = Manga.create().copy(id = MarkReadDeleteConformanceTest.ENTRY, source = 1L)
        val handed = mutableListOf<Chapter>()
        val downloadManager = spyk(
            DownloadManager(
                context = mockk(relaxed = true),
                provider = mockk(),
                cache = mockk(),
                getCategories = mockk {
                    coEvery { await(manga.id) } returns
                        categoryIds.map { Category(id = it, name = "c$it", order = it, flags = 0L) }
                },
                sourceManager = mockk(),
                downloadPreferences = preferences,
                getManga = mockk(),
                getChapter = mockk(),
                downloader = mockk(),
                pendingDeleter = mockk(),
            ),
        ).also { spy ->
            every { spy.deleteChapters(any(), any(), any()) } answers { handed += firstArg<List<Chapter>>() }
        }
        val setReadStatus = SetReadStatus(
            downloadPreferences = preferences,
            deleteDownload = DeleteDownload(mockk { coEvery { get(manga.source) } returns mockk() }, downloadManager),
            mangaRepository = mockk { coEvery { getMangaById(manga.id) } returns manga },
            chapterRepository = mockk { coEvery { updateAll(any()) } returns Unit },
            sourceTracker = mockk(relaxed = true),
        )

        setReadStatus.await(
            true,
            Chapter.create().copy(
                id = MarkReadDeleteConformanceTest.CHAPTER,
                mangaId = manga.id,
                bookmark = bookmarked,
            ),
        )

        return handed.mapTo(HashSet()) { it.id }
    }
}

class NovelMarkReadHalf : MarkReadHalf {
    override fun toString() = "novel"

    override suspend fun markRead(excluded: Set<String>, categoryIds: List<Long>, bookmarked: Boolean): Set<Long> {
        val preferences = NovelPreferences(EmittingPreferenceStore())
        preferences.removeAfterMarkedAsRead().set(true)
        preferences.removeExcludeCategories().set(excluded)
        val categories = mockk<GetNovelCategories> {
            coEvery { awaitByNovelId(MarkReadDeleteConformanceTest.ENTRY) } returns
                categoryIds.map { Category(id = it, name = "c$it", order = it, flags = 0L) }
        }
        val removable = NovelRemovableDownloads(preferences, categories)
        val handed = mutableListOf<NovelChapter>()
        val manager = mockk<NovelDownloadManager> {
            every { deleteChapters(any()) } answers { handed += firstArg<List<NovelChapter>>() }
        }
        val setNovelReadStatus = SetNovelReadStatus(
            chapterRepository = mockk { coEvery { setReadBulk(any(), any()) } returns true },
            deleteAfterRead = DeleteNovelChaptersAfterRead(preferences, removable) { manager },
            sourceTracker = mockk(relaxed = true),
            pushNovelUnread = mockk(relaxed = true),
        )

        setNovelReadStatus.await(true, listOf(chapter(bookmarked)))

        return handed.mapTo(HashSet()) { it.id }
    }

    private fun chapter(bookmarked: Boolean) = NovelChapter(
        id = MarkReadDeleteConformanceTest.CHAPTER,
        novelId = MarkReadDeleteConformanceTest.ENTRY,
        url = "",
        name = "",
        read = false,
        bookmark = bookmarked,
        lastTextProgress = 0L,
        chapterNumber = 1.0,
        sourceOrder = 0L,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )
}
