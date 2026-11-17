package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.MergeScope
import reikai.domain.novel.model.NovelChapterFlags
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.Manga

/**
 * The forward-eligibility rule every reader runs, over the group's answer. What the chapter-list
 * filters keep is pinned beside the details lists in MergedChapterFilterConformanceTest; this covers
 * the settings only the readers have. Row 1 is the copy shown, row 2 another source's copy of it.
 */
class ReaderChapterFiltersTest {

    private data class Row(val id: Long, val read: Boolean = false)

    private val noFilters = ChapterListFilters(TriState.DISABLED, TriState.DISABLED, TriState.DISABLED)

    private fun flags(siblingRead: Boolean = false, onDisk: Set<Long> = emptySet()) = GroupChapterFlags(
        scope = MergeScope.Group,
        pooled = listOf(Row(1L), Row(2L, read = siblingRead)),
        shown = listOf(Row(1L)),
        stitch = listOf(ChapterUnit(1L, 0, 0), ChapterUnit(2L, 0, 1)),
        id = { it.id },
        read = { it.read },
        bookmark = { false },
    ) { onDisk }

    @Test
    fun `skip read passes a chapter another source has read`() {
        val flags = flags(siblingRead = true)

        flags.isForwardEligible(Row(1L), skipRead = true, skipFiltered = false, filters = noFilters) shouldBe false
    }

    @Test
    fun `without skip filtered a filter the chapter fails is not consulted`() {
        val flags = flags(siblingRead = true)
        val unreadOnly = noFilters.copy(unread = TriState.ENABLED_IS)

        flags.isForwardEligible(Row(1L), skipRead = false, skipFiltered = false, filters = unreadOnly) shouldBe true
    }

    @Test
    fun `the downloaded filter keeps a chapter whose other copy is on disk`() {
        val flags = flags(onDisk = setOf(2L))
        val downloadedOnly = noFilters.copy(downloaded = TriState.ENABLED_IS)

        flags.isForwardEligible(Row(1L), skipRead = false, skipFiltered = true, filters = downloadedOnly) shouldBe true
    }

    /** The details lists, the resume target and the skip-filtered step all filter through [ChapterListFilters.keeps]. */
    @Test
    fun `the downloaded filter hides a chapter not on disk`() {
        val downloadedOnly = noFilters.copy(downloaded = TriState.ENABLED_IS)

        downloadedOnly.keeps(isRead = { false }, isBookmarked = { false }, isDownloaded = { false }) shouldBe false
    }

    @Test
    fun `the not-downloaded filter hides a chapter on disk`() {
        val notDownloaded = noFilters.copy(downloaded = TriState.ENABLED_NOT)

        notDownloaded.keeps(isRead = { false }, isBookmarked = { false }, isDownloaded = { true }) shouldBe false
    }

    /** Whether a chapter is on disk costs a folder check, so a list with that filter off never asks. */
    @Test
    fun `a filter that is off is not asked`() {
        noFilters.keeps(isRead = { false }, isBookmarked = { false }, isDownloaded = { error("asked") }) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notDownloadedEntries")
    fun `the Downloaded only switch forces the downloaded filter on`(
        type: String,
        filtersOf: (Boolean) -> ChapterListFilters,
    ) {
        filtersOf(true).downloaded shouldBe TriState.ENABLED_IS
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notDownloadedEntries")
    fun `the entry's own downloaded filter returns with the Downloaded only switch off`(
        type: String,
        filtersOf: (Boolean) -> ChapterListFilters,
    ) {
        filtersOf(false).downloaded shouldBe TriState.ENABLED_NOT
    }

    @Test
    fun `a novel's show-read flag reads as the unread filter's not state`() {
        novelChapterListFilters(NovelChapterFlags.SHOW_READ, 0L, 0L, downloadedOnly = false).unread shouldBe
            TriState.ENABLED_NOT
    }

    @Test
    fun `a list filtered only by the Downloaded only switch counts as filtered`() {
        novelChapterListFilters(0L, 0L, 0L, downloadedOnly = true).isActive shouldBe true
    }

    @Test
    fun `a list with no filter on counts as unfiltered`() {
        novelChapterListFilters(0L, 0L, 0L, downloadedOnly = false).isActive shouldBe false
    }

    @Test
    fun `a picked bookmarked state writes back the novel's own flag`() {
        TriState.ENABLED_NOT.toFlag(NovelChapterFlags.SHOW_BOOKMARKED, NovelChapterFlags.SHOW_NOT_BOOKMARKED) shouldBe
            NovelChapterFlags.SHOW_NOT_BOOKMARKED
    }

    companion object {
        /** Each type's filters, by the Downloaded only switch, for an entry whose own filter hides downloads. */
        @JvmStatic
        fun notDownloadedEntries(): List<Arguments> = listOf(
            Arguments.of(
                "manga",
                { downloadedOnly: Boolean ->
                    Manga.create().copy(chapterFlags = Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
                        .readerChapterFilters(downloadedOnly)
                },
            ),
            Arguments.of(
                "novel",
                { downloadedOnly: Boolean ->
                    novelChapterListFilters(0L, 0L, NovelChapterFlags.SHOW_NOT_DOWNLOADED, downloadedOnly)
                },
            ),
        )
    }
}
