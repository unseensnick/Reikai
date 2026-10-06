package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.details.EntrySourceState

/** The source a novel's header names, browses and opens settings for is the viewed member's own. */
class ViewedNovelSourceTest {

    private val anchorId = 1L
    private val siblingId = 2L

    @Test
    fun `a sibling whose plugin is not installed resolves to no source, not the anchor's`() {
        viewedNovelSource(siblingId, anchorId, siblings = emptyMap(), anchorSource = "anchor-plugin") shouldBe null
    }

    @Test
    fun `an installed sibling resolves to its own plugin`() {
        viewedNovelSource(siblingId, anchorId, mapOf(siblingId to "sibling-plugin"), "anchor-plugin") shouldBe
            "sibling-plugin"
    }

    @Test
    fun `the anchor resolves to its own plugin`() {
        viewedNovelSource(anchorId, anchorId, siblings = emptyMap(), anchorSource = "anchor-plugin") shouldBe
            "anchor-plugin"
    }

    @Test
    fun `an anchor whose plugin was looked up and not found is missing`() {
        novelSourceState(viewedSource = null, isAnchorView = true, anchorMissing = true) shouldBe
            EntrySourceState.Missing
    }

    @Test
    fun `an anchor not looked up yet counts as installed, so the page does not flash a warning`() {
        novelSourceState(viewedSource = null, isAnchorView = true, anchorMissing = false) shouldBe
            EntrySourceState.Installed
    }

    @Test
    fun `a sibling with no plugin is missing`() {
        novelSourceState(viewedSource = null, isAnchorView = false, anchorMissing = false) shouldBe
            EntrySourceState.Missing
    }

    @Test
    fun `a viewed member with its plugin is installed`() {
        novelSourceState(viewedSource = "plugin", isAnchorView = false, anchorMissing = true) shouldBe
            EntrySourceState.Installed
    }
}
