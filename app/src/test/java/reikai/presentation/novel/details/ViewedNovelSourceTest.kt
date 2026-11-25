package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.details.EntrySourceState

/** The source a novel's header names, browses and opens settings for is the viewed member's own. */
class ViewedNovelSourceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("viewed")
    fun `the viewed member resolves to its own plugin, never the anchor's`(
        @Suppress("UNUSED_PARAMETER") case: String,
        viewedId: Long,
        siblings: Map<Long, String>,
        source: String?,
    ) {
        viewedNovelSource(viewedId, ANCHOR, siblings, anchorSource = "anchor-plugin") shouldBe source
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("states")
    fun `the viewed member's source state`(
        @Suppress("UNUSED_PARAMETER") case: String,
        viewedSource: String?,
        isAnchorView: Boolean,
        anchorMissing: Boolean,
        state: EntrySourceState,
    ) {
        novelSourceState(viewedSource, isAnchorView, anchorMissing) shouldBe state
    }

    companion object {
        private const val ANCHOR = 1L
        private const val SIBLING = 2L

        @JvmStatic
        fun viewed() = listOf(
            Arguments.of("a sibling whose plugin is not installed", SIBLING, emptyMap<Long, String>(), null),
            Arguments.of("an installed sibling", SIBLING, mapOf(SIBLING to "sibling-plugin"), "sibling-plugin"),
            Arguments.of("the anchor", ANCHOR, emptyMap<Long, String>(), "anchor-plugin"),
        )

        @JvmStatic
        fun states() = listOf(
            Arguments.of("an anchor looked up and not found", null, true, true, EntrySourceState.Missing),
            // So the page does not flash a warning.
            Arguments.of("an anchor not looked up yet", null, true, false, EntrySourceState.Installed),
            Arguments.of("a sibling with no plugin", null, false, false, EntrySourceState.Missing),
            Arguments.of("a viewed member with its plugin", "plugin", false, true, EntrySourceState.Installed),
        )
    }
}
