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
import reikai.domain.reader.ChapterListFilters
import reikai.domain.reader.isForwardEligible
import reikai.domain.reader.readerChapterFilters
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * A merged series' details list shows one row per chapter, flagged read or bookmarked when any
 * source's copy is, so its chapter filters have to answer on that same group-wide flag. Pinned once
 * over both details lists and the readers' "skip filtered" pass, which all three readers run through
 * one kernel: a filter reading only the row's own flag shows a chapter the row itself draws as read.
 */
class MergedChapterFilterConformanceTest {

    enum class Filter { UNREAD, READ, BOOKMARKED, NOT_BOOKMARKED }

    /** One list's filter over a single chapter whose own row is unread and unbookmarked. */
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
            val manga = Manga.create().copy(id = 1L, source = 100L, chapterFlags = mangaFlagOf(filter))
            val item = ChapterList.Item(
                chapter = Chapter.create().copy(id = CHAPTER_ID, mangaId = 1L),
                downloadState = Download.State.NOT_DOWNLOADED,
                downloadProgress = 0,
                readInAnotherSource = readElsewhere,
                bookmarkedInAnotherSource = bookmarkedElsewhere,
            )
            return listOf(item).applyFilters(manga).map { it.id }.toList()
        }

        override fun toString() = "manga details"
    }

    private object NovelDetails : Probe {
        override fun shownIds(filter: Filter, readElsewhere: Boolean, bookmarkedElsewhere: Boolean): List<Long> =
            listOf(novelChapter(CHAPTER_ID)).sortedAndFiltered(
                novel = novelWith(filter),
                prefs = NovelPreferences(InMemoryPreferenceStore()),
                downloadedChapterIds = emptySet(),
                readInOtherSources = if (readElsewhere) setOf(CHAPTER_ID) else emptySet(),
                bookmarkedInOtherSources = if (bookmarkedElsewhere) setOf(CHAPTER_ID) else emptySet(),
            ).map { it.id }

        override fun toString() = "novel details"
    }

    /**
     * A reader's forward step under "skip filtered", over a real two-source stitch: the shown copy is
     * unread and unbookmarked, the other source's copy carries the flags under test. Each type maps its
     * own filter flags; all three readers then run the one eligibility kernel.
     */
    private abstract class Reader<T>(
        private val name: String,
        private val filtersOf: (Filter) -> ChapterListFilters,
        private val chapter: (id: Long, read: Boolean, bookmark: Boolean) -> T,
        private val id: (T) -> Long,
        private val read: (T) -> Boolean,
        private val bookmark: (T) -> Boolean,
    ) : Probe {
        override fun shownIds(filter: Filter, readElsewhere: Boolean, bookmarkedElsewhere: Boolean): List<Long> {
            val shown = chapter(CHAPTER_ID, false, false)
            val flags = GroupChapterFlags(
                pooled = listOf(shown, chapter(SIBLING_ID, readElsewhere, bookmarkedElsewhere)),
                shown = listOf(shown),
                stitch = listOf(ChapterUnit(CHAPTER_ID, 0, 0), ChapterUnit(SIBLING_ID, 0, 1)),
                id = id,
                read = read,
                bookmark = bookmark,
            ) { emptySet() }
            return listOf(shown)
                .filter {
                    flags.isForwardEligible(it, skipRead = false, skipFiltered = true, filters = filtersOf(filter))
                }
                .map(id)
        }

        override fun toString() = name
    }

    private object MangaReader : Reader<Chapter>(
        name = "manga reader",
        filtersOf = { Manga.create().copy(chapterFlags = mangaFlagOf(it)).readerChapterFilters() },
        chapter = { id, read, bookmark -> Chapter.create().copy(id = id, read = read, bookmark = bookmark) },
        id = { it.id },
        read = { it.read },
        bookmark = { it.bookmark },
    )

    private object NovelReader : Reader<NovelChapter>(
        name = "novel reader",
        filtersOf = { novelWith(it).readerChapterFilters(NovelPreferences(InMemoryPreferenceStore())) },
        chapter = { id, read, bookmark -> novelChapter(id, read, bookmark) },
        id = { it.id },
        read = { it.read },
        bookmark = { it.bookmark },
    )

    companion object {
        private const val CHAPTER_ID = 1L
        private const val SIBLING_ID = 2L
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"

        @JvmStatic
        fun probes() = listOf(MangaDetails, NovelDetails, MangaReader, NovelReader)

        fun mangaFlagOf(filter: Filter) = when (filter) {
            Filter.UNREAD -> Manga.CHAPTER_SHOW_UNREAD
            Filter.READ -> Manga.CHAPTER_SHOW_READ
            Filter.BOOKMARKED -> Manga.CHAPTER_SHOW_BOOKMARKED
            Filter.NOT_BOOKMARKED -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        fun novelFlagOf(filter: Filter) = when (filter) {
            Filter.UNREAD -> NovelChapterFlags.SHOW_UNREAD
            Filter.READ -> NovelChapterFlags.SHOW_READ
            Filter.BOOKMARKED -> NovelChapterFlags.SHOW_BOOKMARKED
            Filter.NOT_BOOKMARKED -> NovelChapterFlags.SHOW_NOT_BOOKMARKED
        }

        /** Local sort and filter bits, so the novel's own flags decide rather than the global defaults. */
        fun novelWith(filter: Filter) = Novel.create().copy(
            chapterFlags = NovelChapterFlags.FILTER_LOCAL or NovelChapterFlags.SORT_LOCAL or novelFlagOf(filter),
        )

        fun novelChapter(id: Long, read: Boolean = false, bookmark: Boolean = false) = NovelChapter(
            id = id,
            novelId = id,
            url = "/$id",
            name = "Chapter 1",
            read = read,
            bookmark = bookmark,
            lastTextProgress = 0L,
            chapterNumber = 1.0,
            sourceOrder = 0L,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )
    }
}
