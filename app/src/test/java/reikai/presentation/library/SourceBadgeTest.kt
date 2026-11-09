package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelSource
import tachiyomi.domain.source.model.Source
import tachiyomi.source.local.LocalSource

class SourceBadgeTest {

    private fun novelSource(id: String, site: String, iconUrl: String?) = mockk<NovelSource> {
        every { this@mockk.id } returns id
        every { this@mockk.site } returns site
        every { this@mockk.iconUrl } returns iconUrl
    }

    private val iconless = novelSource("plugin", "https://www.example.com/", iconUrl = null)
    private val sameSite = novelSource("ireader:1", "https://example.com", iconUrl = "https://cdn/icon.png")
    private val otherSite = novelSource("other", "https://elsewhere.org", iconUrl = "https://cdn/other.png")

    @Test
    fun `a novel whose source is not installed draws the missing-source badge`() {
        novelSourceBadge(null, emptyMap()) shouldBe SourceBadge.Missing
    }

    @Test
    fun `an installed novel source with no icon draws the generic badge, as a manga one does`() {
        novelSourceBadge(iconless, installedIconsBySite(listOf(iconless, otherSite))) shouldBe SourceBadge.Generic
    }

    @Test
    fun `an iconless novel source borrows the icon of another installed source for the same site`() {
        novelSourceBadge(iconless, installedIconsBySite(listOf(iconless, sameSite))) shouldBe
            SourceBadge.Icon("https://cdn/icon.png")
    }

    @Test
    fun `a novel source keeps its own icon`() {
        novelSourceBadge(otherSite, installedIconsBySite(listOf(sameSite))) shouldBe
            SourceBadge.Icon("https://cdn/other.png")
    }

    @Test
    fun `either type's local source draws the one local badge`() {
        listOf(
            mangaSourceBadge(Source(LocalSource.ID, "other", "Local", supportsLatest = false, isStub = false)),
            novelSourceBadge(novelSource(LocalNovelSource.ID, "", iconUrl = null), emptyMap()),
        ) shouldBe listOf(SourceBadge.Local, SourceBadge.Local)
    }

    private fun badges(
        source: SourceBadge?,
        merged: List<SourceBadge> = emptyList(),
        isLocal: Boolean = false,
    ) = LibraryItem.Badges(
        downloadCount = 0,
        unreadCount = 0,
        isLocal = isLocal,
        sourceLanguage = "",
        source = source,
        mergedSources = merged,
    )

    private val manga = SourceBadge.Manga(Source(1, "en", "A", supportsLatest = false, isStub = false))

    @Test
    fun `a merged row spends one slot per grouped source, whatever the content type`() {
        badges(manga, merged = listOf(manga, SourceBadge.Generic, SourceBadge.Missing))
            .endSources(isMerged = true).size shouldBe 3
    }

    @Test
    fun `an unmerged row spends one slot on its own badge`() {
        badges(SourceBadge.Generic).endSources(isMerged = false).size shouldBe 1
    }

    @Test
    fun `a row with the source badge off spends none`() {
        badges(null).endSources(isMerged = false).size shouldBe 0
    }

    @Test
    fun `a local row leaves out the local source the folder badge already names`() {
        badges(SourceBadge.Local, isLocal = true).endSources(isMerged = false) shouldBe emptyList()
    }

    @Test
    fun `a local merged row leaves out the local source and keeps the others`() {
        badges(SourceBadge.Local, merged = listOf(SourceBadge.Local, manga), isLocal = true)
            .endSources(isMerged = true) shouldBe listOf(manga)
    }

    @Test
    fun `a row without the folder badge keeps its local source`() {
        badges(SourceBadge.Local, merged = listOf(manga, SourceBadge.Local))
            .endSources(isMerged = true) shouldBe listOf(manga, SourceBadge.Local)
    }
}
