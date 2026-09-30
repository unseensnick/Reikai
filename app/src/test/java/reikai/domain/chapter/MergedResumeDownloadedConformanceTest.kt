package reikai.domain.chapter

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.renderStoredStitch
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.downloadedChapterIds
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadCache
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * The library continue button over both content types under a Downloaded filter, on a merged series of
 * two sources where chapter 1's only copy on disk is the second source's. The reader opens that copy
 * (CopyToOpen), so the button must resume there rather than pass chapter 1 over as not downloaded.
 */
class MergedResumeDownloadedConformanceTest {

    @BeforeEach
    fun setUp() {
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter } returns TriState.ENABLED_IS
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(DOWNLOADED_FILTER_FILE)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `continue resumes a chapter whose only copy on disk is another source's`(case: LibraryResumeCase) =
        runTest {
            case.resume(emptySet()) shouldBe 1L
        }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"

        // Chapter 1: leading copy 1 (not on disk), sibling copy 21 (on disk). Chapter 2: leading copy 2 only.
        private val stitch = listOf(ChapterUnit(1L, 0, 0), ChapterUnit(21L, 0, 1), ChapterUnit(2L, 1, 0))
        private val onDisk = setOf(21L)

        private val manga = LibraryResumeCase("manga") {
            val leading = Manga.create().copy(id = 1L, source = 7L, title = "Leading")
            val other = Manga.create().copy(id = 2L, source = 8L, title = "Other")
            fun chapter(id: Long, owner: Manga, number: Double) = Chapter.create().copy(
                id = id,
                mangaId = owner.id,
                url = "u$id",
                name = "$id",
                chapterNumber = number,
                sourceOrder = 10L - number.toLong(),
            )
            val pooled = listOf(chapter(1L, leading, 1.0), chapter(2L, leading, 2.0), chapter(21L, other, 1.0))
            val group = MergedChapterProvider.Group(
                mangaById = mapOf(1L to leading, 2L to other),
                chapters = renderStoredStitch(pooled, stitch) { it.id },
                sourceNameByMangaId = emptyMap(),
                stitch = stitch,
                pooledChapters = pooled,
            )
            val downloadManager = mockk<DownloadManager> {
                every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
                    firstArg<String>().toLong() in onDisk
                }
            }
            // As LibraryViewModel.getNextUnreadChapter calls it.
            group.chapters.getNextUnread(manga = leading, downloadManager, group)?.id
        }

        private val novel = LibraryResumeCase("novel") {
            fun chapter(id: Long, novelId: Long, number: Double) = NovelChapter(
                id = id,
                novelId = novelId,
                url = "u$id",
                name = "Ch $id",
                read = false,
                bookmark = false,
                lastTextProgress = 0L,
                chapterNumber = number,
                sourceOrder = number.toLong(),
                dateFetch = 0L,
                dateUpload = 0L,
                page = "",
            )
            val chapterRepository = mockk<NovelChapterRepository> {
                coEvery { getByNovelId(1L) } returns listOf(chapter(1L, 1L, 1.0), chapter(2L, 1L, 2.0))
                coEvery { getByNovelId(2L) } returns listOf(chapter(21L, 2L, 1.0))
            }
            val novelRepository = mockk<NovelRepository> {
                coEvery { getById(any()) } answers { Novel.create().copy(id = firstArg(), source = "ln-source") }
            }
            val mergeManager = mockk<NovelMergeManager> {
                coEvery { computeRelatedIds(any()) } returns
                    longArrayOf(1L, 2L)
            }
            // A copy is on disk only under its own novel's folder, as the index answers it.
            val cache = mockk<NovelDownloadCache> {
                every { downloadedChapterIds(any<Novel>(), any()) } answers {
                    val owner = firstArg<Novel>()
                    secondArg<List<NovelChapter>>().filter { it.novelId == owner.id && it.id in onDisk }
                        .mapTo(HashSet()) { it.id }
                }
            }
            val render = NovelMergedChapterProvider(mockk(), mockk(), mockk())
            val mergedChapterProvider = mockk<NovelMergedChapterProvider> {
                coEvery { stitchOf(any()) } returns stitch
                every { merged(any(), any()) } answers { render.merged(firstArg(), secondArg()) }
            }
            GetNextNovelChapter(
                chapterRepository,
                novelRepository,
                NovelPreferences(InMemoryPreferenceStore(sequenceOf())),
                mergeManager,
                mergedChapterProvider,
            ).awaitFirstUnreadInGroup(1L, downloadedOnly = true, downloadedIds = cache::downloadedChapterIds)?.id
        }

        @JvmStatic
        fun cases() = listOf(manga, novel)
    }
}
