package reikai.presentation.library.hidden

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.HideSeries
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

class HiddenEntriesViewModelTest {

    private val unhidden = mutableListOf<Set<EntryId>>()

    // Stubbed before virtualDispatchers, which a later coEvery would leave Default a bare mock behind.
    private val hideSeries = mockk<HideSeries> {
        every { subscribeAll() } returns emptyFlow()
        coEvery { unhide(any()) } answers { unhidden += firstArg<Collection<EntryId>>().toSet() }
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `Unhide shows every selected series again`() = runTest(virtualDispatchers()) {
        val model = HiddenEntriesViewModel(
            initialContentType = ContentType.ALL,
            mangaLibraryRows = mockk { every { rows } returns emptyFlow() },
            novelLibraryRows = mockk { every { rows } returns emptyFlow() },
            getEntryCustomInfo = mockk { every { subscribeAll() } returns emptyFlow() },
            reikaiLibraryPreferences = ReikaiLibraryPreferences(InMemoryPreferenceStore()),
            hideSeries = hideSeries,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            getTracksPerManga = mockk(),
            getNovelTracks = mockk(),
            trackerManager = mockk(),
            appScope = this,
        )
        model.toggleSelection(EntryId.Manga(1))
        model.toggleSelection(EntryId.Novel(1))

        model.unhideSelected()
        advanceUntilIdle()

        unhidden shouldBe listOf(setOf(EntryId.Manga(1), EntryId.Novel(1)))
    }
}
