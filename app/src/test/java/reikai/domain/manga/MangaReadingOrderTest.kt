package reikai.domain.manga

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.chapter.ReadingOrder
import reikai.domain.merge.ChapterUnit
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * A merged manga's chapters in reading order, the one rule the library's "download next", the reader's
 * download-ahead and the Recents target all walk. It has to be the order the library resumes in, or
 * each of them reaches a different chapter "next" for the same series.
 */
class MangaReadingOrderTest {

    @BeforeEach
    fun setUp() {
        // The downloaded filter reads a global preference through the app graph; it is not under test.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter } returns TriState.DISABLED
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(DOWNLOADED_FILTER_FILE)
    }

    @Test
    fun `a merged group sorted by upload date is walked earliest upload first`() {
        group().inReadingOrder(mangaSortedBy(Manga.CHAPTER_SORTING_UPLOAD_DATE)).map { it.id } shouldBe
            listOf(BETA, GAMMA, ALPHA)
    }

    @Test
    fun `the default sort walks a merged group in stitch order`() {
        group().inReadingOrder(Manga.create()).map { it.id } shouldBe listOf(GAMMA, ALPHA, BETA)
    }

    @Test
    fun `the next chapter in reading order is the one the library resumes at`() {
        val manga = mangaSortedBy(Manga.CHAPTER_SORTING_ALPHABET)
        val chapters = group(read = setOf(BETA))

        ReadingOrder.nextToRead(chapters.inReadingOrder(manga)) { it.read } shouldBe
            chapters.getNextUnread(manga, mockk())
    }

    @Test
    fun `the library resumes a merged group by its chapter sort, not the stitch`() {
        group().getNextUnread(mangaSortedBy(Manga.CHAPTER_SORTING_ALPHABET), mockk())?.id shouldBe ALPHA
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("tiedOrders")
    fun `every caller resumes a tie at the chapter its source lists first to read`(
        caller: NextChapterCaller,
        mode: String,
        manga: Manga,
    ) = runTest {
        caller.next(manga, tied()) shouldBe EARLIEST_BY_SOURCE
    }

    private fun mangaSortedBy(sorting: Long) =
        Manga.create().copy(id = 1L, chapterFlags = sorting or Manga.CHAPTER_SORT_ASC)

    /**
     * Two sources' chapters as the stitch orders them, newest first, with the source order the provider
     * restamps. No other sort agrees with that order, so a walk that ignores the sort shows.
     */
    private fun group(read: Set<Long> = emptySet()): List<Chapter> {
        val stitched = listOf(
            chapter(BETA, mangaId = 1L, name = "Beta", upload = 100L),
            chapter(ALPHA, mangaId = 2L, name = "Alpha", upload = 300L),
            chapter(GAMMA, mangaId = 1L, name = "Gamma", upload = 200L),
        ).map { it.copy(read = it.id in read) }
        val stitch = stitched.mapIndexed { index, chapter -> ChapterUnit(chapter.id, index, 0) }
        return MergedChapterProvider(mockk(), mockk(), mockk(), mockk(), mockk(), mockk()).merged(stitched, stitch)
    }

    private fun chapter(id: Long, mangaId: Long, name: String, upload: Long) =
        Chapter.create().copy(
            id = id,
            mangaId = mangaId,
            name = name,
            dateUpload = upload,
            chapterNumber = id.toDouble(),
        )

    /** Where one caller resumes, asked of the same chapters. */
    class NextChapterCaller(private val label: String, val next: suspend (Manga, List<Chapter>) -> Long?) {
        override fun toString() = label
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val BETA = 1L
        private const val ALPHA = 2L
        private const val GAMMA = 3L
        private const val EARLIEST_BY_SOURCE = 12L

        /**
         * Three chapters no sort key tells apart, in the order the database hands them back: one fetch
         * inserts newest first, so the chapter the source lists as earliest comes out last.
         */
        private fun tied(): List<Chapter> = listOf(10L, 11L, 12L).mapIndexed { index, id ->
            Chapter.create().copy(
                id = id,
                mangaId = 1L,
                name = "Chapter",
                chapterNumber = 1.0,
                dateUpload = 100L,
                sourceOrder = index.toLong(),
            )
        }

        private val callers = listOf(
            // The reader's list, the library's and Recents' targets, and download next from details.
            NextChapterCaller("reader") { manga, chapters ->
                ReadingOrder.nextToRead(chapters.inReadingOrder(manga)) { it.read }?.id
            },
            NextChapterCaller("library continue reading") { manga, chapters ->
                chapters.getNextUnread(manga, mockk())?.id
            },
            NextChapterCaller("details resume") { manga, chapters ->
                chapters.map { ChapterList.Item(it, Download.State.NOT_DOWNLOADED, 0) }.getNextUnread(manga)?.id
            },
            NextChapterCaller("download next, one source") { manga, chapters ->
                val chapterRepository = mockk<ChapterRepository> {
                    coEvery { getChapterByMangaId(manga.id, any()) } returns chapters
                }
                val mangaRepository = mockk<MangaRepository> { coEvery { getMangaById(manga.id) } returns manga }
                GetNextChapters(GetChaptersByMangaId(chapterRepository), GetManga(mangaRepository), mockk())
                    .await(manga.id).firstOrNull()?.id
            },
        )

        @JvmStatic
        fun tiedOrders(): List<Arguments> = callers.flatMap { caller ->
            listOf(
                "by source" to Manga.CHAPTER_SORTING_SOURCE,
                "by number" to Manga.CHAPTER_SORTING_NUMBER,
                "by upload date" to Manga.CHAPTER_SORTING_UPLOAD_DATE,
                "alphabetically" to Manga.CHAPTER_SORTING_ALPHABET,
            ).flatMap { (mode, sorting) ->
                listOf(Manga.CHAPTER_SORT_ASC to "", Manga.CHAPTER_SORT_DESC to " newest first").map { (dir, label) ->
                    Arguments.of(caller, mode + label, Manga.create().copy(id = 1L, chapterFlags = sorting or dir))
                }
            }
        }
    }
}
