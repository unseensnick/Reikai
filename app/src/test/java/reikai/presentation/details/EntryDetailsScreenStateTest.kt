package reikai.presentation.details

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.entry.EntryId
import reikai.domain.merge.DownloadTargets

/** The page's download gate and cover anchoring derive from the one viewed member, for both types. */
class EntryDetailsScreenStateTest {

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource("Installed, true", "Missing, false", "Local, false")
    fun `only an installed viewed source offers downloads, a local one's files being the series itself`(
        state: EntrySourceState,
        downloadable: Boolean,
    ) {
        loaded(sourceState = state).chaptersDownloadable shouldBe downloadable
    }

    // A local source's row keeps Mihon's disabled control; a missing one's keeps it only for what is on disk.
    @ParameterizedTest(name = "{0}, {1}: {2}")
    @CsvSource("Missing, NOT_DOWNLOADED, false", "Missing, DOWNLOADED, true", "Local, NOT_DOWNLOADED, true")
    fun `a row's download control follows its source and what it has on disk`(
        state: EntrySourceState,
        download: Download.State,
        offered: Boolean,
    ) {
        loaded(sourceState = state).rowOffersDownload(ROW, download) shouldBe offered
    }

    @ParameterizedTest(name = "viewing {0}: {1}")
    @CsvSource("$ANCHOR, true", "$SIBLING, false")
    fun `the cover is anchored only while the page shows the anchor`(viewed: Long, anchored: Boolean) {
        loaded(viewedEntryId = viewed).isCoverAnchored shouldBe anchored
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
