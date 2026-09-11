package reikai.domain.merge

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.sortedAndFiltered
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * A merged series' details list shows one row per chapter, flagged read or bookmarked when any
 * source's copy is, so its chapter filters have to answer on that same group-wide flag. Pinned once
 * over both details lists: a filter reading only the row's own flag shows a chapter the row itself
 * draws as read. The readers apply the same rule and are not covered here.
 */
class MergedChapterFilterConformanceTest {

    enum class Filter { UNREAD, READ, BOOKMARKED, NOT_BOOKMARKED }

    /** One details list's filter over a single chapter whose own row is unread and unbookmarked. */
    interface Probe {
        fun shownIds(filter: Filter, readElsewhere: Boolean, bookmarkedElsewhere: Boolean): List<Long>
    }

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the unread filter hides a chapter another source has read`(probe: Probe) {
        probe.shownIds(Filter.UNREAD, readElsewhere = true, bookmarkedElsewhere = false) shouldBe emptyList()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the read filter shows a chapter another source has read`(probe: Probe) {
        probe.shownIds(Filter.READ, readElsewhere = true, bookmarkedElsewhere = false) shouldBe listOf(CHAPTER_ID)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the bookmarked filter shows a chapter another source has bookmarked`(probe: Probe) {
        probe.shownIds(Filter.BOOKMARKED, readElsewhere = false, bookmarkedElsewhere = true) shouldBe
            listOf(CHAPTER_ID)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the not-bookmarked filter hides a chapter another source has bookmarked`(probe: Probe) {
        probe.shownIds(Filter.NOT_BOOKMARKED, readElsewhere = false, bookmarkedElsewhere = true) shouldBe
            emptyList()
    }

    private object MangaDetails : Probe {
        override fun shownIds(filter: Filter, readElsewhere: Boolean, bookmarkedElsewhere: Boolean): List<Long> {
            val manga = Manga.create().copy(id = 1L, source = 100L, chapterFlags = flagOf(filter))
            val item = ChapterList.Item(
                chapter = Chapter.create().copy(id = CHAPTER_ID, mangaId = 1L),
                downloadState = Download.State.NOT_DOWNLOADED,
                downloadProgress = 0,
                readInAnotherSource = readElsewhere,
                bookmarkedInAnotherSource = bookmarkedElsewhere,
            )
            return listOf(item).applyFilters(manga).map { it.id }.toList()
        }

        private fun flagOf(filter: Filter) = when (filter) {
            Filter.UNREAD -> Manga.CHAPTER_SHOW_UNREAD
            Filter.READ -> Manga.CHAPTER_SHOW_READ
            Filter.BOOKMARKED -> Manga.CHAPTER_SHOW_BOOKMARKED
            Filter.NOT_BOOKMARKED -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        override fun toString() = "manga details"
    }

    private object NovelDetails : Probe {
        override fun shownIds(filter: Filter, readElsewhere: Boolean, bookmarkedElsewhere: Boolean): List<Long> {
            // Local sort and filter bits, so the novel's own flags decide rather than the global defaults.
            val novel = Novel.create().copy(
                chapterFlags = NovelChapterFlags.FILTER_LOCAL or NovelChapterFlags.SORT_LOCAL or flagOf(filter),
            )
            val chapter = NovelChapter(
                id = CHAPTER_ID,
                novelId = 1L,
                url = "/1",
                name = "Chapter 1",
                read = false,
                bookmark = false,
                lastTextProgress = 0L,
                chapterNumber = 1.0,
                sourceOrder = 0L,
                dateFetch = 0L,
                dateUpload = 0L,
                page = "",
            )
            return listOf(chapter).sortedAndFiltered(
                novel = novel,
                prefs = NovelPreferences(InMemoryPreferenceStore()),
                downloadedChapterIds = emptySet(),
                readInOtherSources = if (readElsewhere) setOf(CHAPTER_ID) else emptySet(),
                bookmarkedInOtherSources = if (bookmarkedElsewhere) setOf(CHAPTER_ID) else emptySet(),
            ).map { it.id }
        }

        private fun flagOf(filter: Filter) = when (filter) {
            Filter.UNREAD -> NovelChapterFlags.SHOW_UNREAD
            Filter.READ -> NovelChapterFlags.SHOW_READ
            Filter.BOOKMARKED -> NovelChapterFlags.SHOW_BOOKMARKED
            Filter.NOT_BOOKMARKED -> NovelChapterFlags.SHOW_NOT_BOOKMARKED
        }

        override fun toString() = "novel details"
    }

    companion object {
        private const val CHAPTER_ID = 1L
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"

        @JvmStatic
        fun probes() = listOf(MangaDetails, NovelDetails)
    }
}
