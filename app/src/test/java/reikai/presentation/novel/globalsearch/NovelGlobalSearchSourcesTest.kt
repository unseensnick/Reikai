package reikai.presentation.novel.globalsearch

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.novel.host.NovelItem
import reikai.novel.source.NovelSource
import reikai.presentation.novel.globalsearch.NovelGlobalSearchScreenModel.SourceFilter

class NovelGlobalSearchSourcesTest {

    private fun source(id: String, name: String) = mockk<NovelSource> {
        every { this@mockk.id } returns id
        every { this@mockk.name } returns name
    }

    @Test
    fun `All keeps every source, pinned first then alphabetical`() {
        val all = listOf(source("c", "Charlie"), source("a", "Alpha"), source("b", "Bravo"))
        val result = selectGlobalSearchSources(all, pinned = setOf("c"), SourceFilter.All)
        result.map { it.id } shouldBe listOf("c", "a", "b")
    }

    @Test
    fun `PinnedOnly keeps only pinned sources`() {
        val all = listOf(source("a", "Alpha"), source("b", "Bravo"), source("c", "Charlie"))
        val result = selectGlobalSearchSources(all, pinned = setOf("b", "c"), SourceFilter.PinnedOnly)
        result.map { it.id } shouldBe listOf("b", "c")
    }

    @Test
    fun `PinnedOnly with no pins yields nothing`() {
        val all = listOf(source("a", "Alpha"))
        selectGlobalSearchSources(all, pinned = emptySet(), SourceFilter.PinnedOnly) shouldBe emptyList()
    }

    @Test
    fun `has-results off shows every row`() {
        val r = SourceSearchResult(source("a", "Alpha"), SearchState.Loading)
        r.isVisible(onlyShowHasResults = false) shouldBe true
    }

    @Test
    fun `has-results on hides loading and errored sources`() {
        val src = source("a", "Alpha")
        SourceSearchResult(src, SearchState.Loading).isVisible(true) shouldBe false
        SourceSearchResult(src, SearchState.Error("x")).isVisible(true) shouldBe false
    }

    @Test
    fun `has-results on hides empty results but keeps non-empty`() {
        val src = source("a", "Alpha")
        SourceSearchResult(src, SearchState.Success(emptyList())).isVisible(true) shouldBe false
        SourceSearchResult(src, SearchState.Success(listOf(mockk<NovelItem>()))).isVisible(true) shouldBe true
    }
}
