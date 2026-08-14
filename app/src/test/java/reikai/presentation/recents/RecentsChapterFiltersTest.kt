package reikai.presentation.recents

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
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
        progress: RecentsProgress? = null,
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

    /**
     * A read row keeps no progress (that is a display rule: a finished chapter says nothing about how
     * far in you are), so reading the stored value alone would report every finished chapter as never
     * started, which is the one thing "started" cannot mean.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("startedProbes")
    fun `a chapter read to the end has started`(probe: StartedProbe) {
        val read = chapterState(read = true, bookmark = false, progress = probe.at(9))

        RecentsChapterFilters(started = TriState.ENABLED_IS).keeps(read) shouldBe true
    }

    companion object {
        @JvmStatic
        fun startedProbes() = listOf(
            StartedProbe("pages") { RecentsProgress.Pages(it, pageCount = 38) },
            StartedProbe("percent") { RecentsProgress.Percent(it) },
        )
    }
}

/** One content type's unit of reading progress, so the started rule is pinned once over both. */
class StartedProbe(private val label: String, val at: (Long) -> RecentsProgress) {
    override fun toString() = label
}
