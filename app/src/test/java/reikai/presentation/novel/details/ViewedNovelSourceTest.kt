package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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
}
