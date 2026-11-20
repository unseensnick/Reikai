package reikai.presentation.details

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.merge.DownloadTargets

/** The page's download gate and cover anchoring derive from the one viewed member, for both types. */
class EntryDetailsScreenStateTest {

    @Test
    fun `an installed viewed source offers downloads`() {
        loaded(sourceState = EntrySourceState.Installed).chaptersDownloadable shouldBe true
    }

    @Test
    fun `a missing viewed source offers no downloads`() {
        loaded(sourceState = EntrySourceState.Missing).chaptersDownloadable shouldBe false
    }

    @Test
    fun `a local viewed source offers no downloads, its files being the series itself`() {
        loaded(sourceState = EntrySourceState.Local).chaptersDownloadable shouldBe false
    }

    @Test
    fun `a missing source's row with nothing downloaded offers no download control`() {
        loaded(sourceState = EntrySourceState.Missing).rowOffersDownload(ROW, Download.State.NOT_DOWNLOADED) shouldBe
            false
    }

    @Test
    fun `a missing source's downloaded row keeps its download control`() {
        loaded(sourceState = EntrySourceState.Missing).rowOffersDownload(ROW, Download.State.DOWNLOADED) shouldBe true
    }

    @Test
    fun `a local source's row keeps Mihon's disabled download control`() {
        loaded(sourceState = EntrySourceState.Local).rowOffersDownload(ROW, Download.State.NOT_DOWNLOADED) shouldBe true
    }

    @Test
    fun `the cover is anchored while the page shows the anchor`() {
        loaded(viewedEntryId = ANCHOR).isCoverAnchored shouldBe true
    }

    @Test
    fun `the cover is not anchored while the page shows a sibling`() {
        loaded(viewedEntryId = SIBLING).isCoverAnchored shouldBe false
    }

    @Test
    fun `a selection offers no Download for a row no installed source holds`() {
        loaded(
            items = listOf(chapter(ROW, Download.State.NOT_DOWNLOADED)),
            selection = setOf(ROW),
            downloadTargets = DownloadTargets(unavailable = setOf(ROW)),
        ).selectionOffers.download shouldBe false
    }

    @Test
    fun `a selection offers Download for a row an installed source fetches`() {
        loaded(
            items = listOf(chapter(ROW, Download.State.NOT_DOWNLOADED)),
            selection = setOf(ROW),
        ).selectionOffers.download shouldBe true
    }

    private fun loaded(
        sourceState: EntrySourceState = EntrySourceState.Installed,
        viewedEntryId: Long = ANCHOR,
        items: List<EntryChapterListItem> = emptyList(),
        selection: Set<Long> = emptySet(),
        downloadTargets: DownloadTargets = DownloadTargets.OWN,
    ) = loadedDetails(items).let {
        it.copy(
            entryId = EntryId.Manga(ANCHOR),
            viewedEntryId = viewedEntryId,
            details = it.details.copy(header = it.details.header.copy(sourceState = sourceState)),
            selection = selection,
            downloadTargets = downloadTargets,
        )
    }

    private fun chapter(id: Long, downloadState: Download.State) = EntryChapterListItem.Chapter(
        id = id,
        name = "",
        subtitle = null,
        read = false,
        bookmark = false,
        dateUpload = null,
        chapterNumber = 1.0,
        progress = null,
        downloadState = downloadState,
        numberHinted = false,
    )

    private companion object {
        const val ANCHOR = 1L
        const val SIBLING = 2L
        const val ROW = 10L
    }
}
