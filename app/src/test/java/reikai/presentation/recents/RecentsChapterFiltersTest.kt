package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.reader.ChapterProgress
import tachiyomi.core.common.preference.TriState

/**
 * The chapter-state filters, pinned once over both content types' progress units. What they are for is
 * the lanes no query filters: the updated lane answers these in SQL, so a mixed feed used to narrow its
 * update rows and leave every read row standing.
 */
class RecentsChapterFiltersTest {

    private fun state(
        read: Boolean = false,
        bookmark: Boolean = false,
        progress: ChapterProgress? = null,
    ) = RecentsChapterState(read = read, bookmark = bookmark, progress = progress)

    private fun RecentsChapterFilters.keeps(state: RecentsChapterState, downloaded: Boolean = false) =
        matches(state) { downloaded }

    @Test
    fun `no filter set judges nothing`() {
        RecentsChapterFilters.NONE.isActive shouldBe false
    }

    @Test
    fun `filtering to unread drops a chapter already read`() {
        val filters = RecentsChapterFilters(unread = TriState.ENABLED_IS)

        (filters.keeps(state(read = false)) to filters.keeps(state(read = true))) shouldBe (true to false)
    }

    @Test
    fun `filtering to read drops a chapter still unread`() {
        val filters = RecentsChapterFilters(unread = TriState.ENABLED_NOT)

        (filters.keeps(state(read = true)) to filters.keeps(state(read = false))) shouldBe (true to false)
    }

    @Test
    fun `filtering to bookmarked drops the rest`() {
        val filters = RecentsChapterFilters(bookmarked = TriState.ENABLED_IS)

        (filters.keeps(state(bookmark = true)) to filters.keeps(state())) shouldBe (true to false)
    }

    @Test
    fun `filtering to downloaded asks the caller, which owns the lookup`() {
        val filters = RecentsChapterFilters(downloaded = TriState.ENABLED_IS)

        (filters.keeps(state(), downloaded = true) to filters.keeps(state())) shouldBe (true to false)
    }

    /**
     * The lookup costs a queue and a disk read on the read lane, so nothing may pay it while the filter
     * is off. Written as a count rather than a value because the value would be right either way.
     */
    @Test
    fun `a filter set to anything else never asks whether a chapter is downloaded`() {
        var asked = 0

        RecentsChapterFilters(unread = TriState.ENABLED_IS).matches(state()) {
            asked++
            true
        }

        asked shouldBe 0
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startedProbes")
    fun `an untouched chapter has not started`(probe: StartedProbe) {
        RecentsChapterFilters(started = TriState.ENABLED_IS).keeps(state(progress = probe.at(0))) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startedProbes")
    fun `a chapter stopped part way has started`(probe: StartedProbe) {
        RecentsChapterFilters(started = TriState.ENABLED_IS).keeps(state(progress = probe.at(1))) shouldBe true
    }

    /** Started is Mihon's in-progress question, which the updated lane's query asks the same way. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("startedProbes")
    fun `a chapter read to the end is not in progress`(probe: StartedProbe) {
        val read = chapterState(read = true, bookmark = false, progress = probe.at(9))

        RecentsChapterFilters(started = TriState.ENABLED_IS).keeps(read) shouldBe false
    }

    // The bulk bar's two predicates. Every unread row carries a progress value, zero included, so a
    // null check offered Mark as unread on a chapter nobody had opened.

    @ParameterizedTest(name = "{0}")
    @MethodSource("startedProbes")
    fun `a chapter never opened has nothing to mark unread`(probe: StartedProbe) {
        offersMarkUnread(listOf(chapterState(read = false, bookmark = false, progress = probe.at(0)))) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startedProbes")
    fun `a chapter opened past its start can be marked unread`(probe: StartedProbe) {
        offersMarkUnread(listOf(chapterState(read = false, bookmark = false, progress = probe.at(3)))) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startedProbes")
    fun `a finished chapter can be marked unread`(probe: StartedProbe) {
        offersMarkUnread(listOf(chapterState(read = true, bookmark = false, progress = probe.at(9)))) shouldBe true
    }

    /** Each row's own control downloads a finished chapter, and so does upstream's Updates bar. */
    @Test
    fun `a finished chapter not on disk can be downloaded from the selection`() {
        offersDownload(listOf(Download.State.NOT_DOWNLOADED)) shouldBe true
    }

    @Test
    fun `a selection already on disk offers no download`() {
        offersDownload(listOf(Download.State.DOWNLOADED)) shouldBe false
    }

    @Test
    fun `a selected row with no chapter offers no download`() {
        offersDownload(listOf(null)) shouldBe false
    }

    companion object {
        @JvmStatic
        fun startedProbes() = listOf(
            StartedProbe("pages") { ChapterProgress.Pages(it, pageCount = 38) },
            StartedProbe("percent") { ChapterProgress.Percent(it) },
        )
    }
}

/** One content type's unit of reading progress, so the started rule is pinned once over both. */
class StartedProbe(private val label: String, val at: (Long) -> ChapterProgress) {
    override fun toString() = label
}
