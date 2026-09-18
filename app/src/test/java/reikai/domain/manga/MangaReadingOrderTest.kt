package reikai.domain.manga

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.chapter.ReadingOrder
import reikai.domain.merge.ChapterUnit
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

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

    private companion object {
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        const val BETA = 1L
        const val ALPHA = 2L
        const val GAMMA = 3L
    }
}
