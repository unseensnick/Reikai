package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.ui.browse.BrowseShowExtensionsEvent
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibrarySearchEvent
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.presentation.RecordingResultBus
import reikai.presentation.recents.RecentsMode
import reikai.presentation.recents.RecentsModeEvent
import reikai.presentation.recents.RecentsTab
import reikai.presentation.resultKey

/** A tab event opens its tab and sends on what it asks of that tab, to that tab's own collector. */
class HomeTabEventsTest {

    private val recorder = RecordingResultBus()

    @Test
    fun `a library search opens the library and searches it on the type's chip`() {
        val tab = HomeScreen.tabFor(
            TabEvent.Library(query = "q", contentType = ContentType.NOVELS),
            false,
            recorder.bus,
        )

        (tab to recorder.sent) shouldBe
            (LibraryTab to listOf(resultKey<LibrarySearchEvent>() to LibrarySearchEvent("q", ContentType.NOVELS)))
    }

    @Test
    fun `opening the library alone searches nothing`() {
        val tab = HomeScreen.tabFor(TabEvent.Library(), false, recorder.bus)

        (tab to recorder.sent) shouldBe (LibraryTab to emptyList<Pair<String, Any?>>())
    }

    @Test
    fun `the extensions shortcut opens Browse on Extensions`() {
        val tab = HomeScreen.tabFor(TabEvent.Browse(toExtensions = true), false, recorder.bus)

        (tab to recorder.sent) shouldBe
            (BrowseTab to listOf(resultKey<BrowseShowExtensionsEvent>() to BrowseShowExtensionsEvent))
    }

    @Test
    fun `a recents shortcut opens the combined tab on its mode`() {
        val tab = HomeScreen.tabFor(TabEvent.History, true, recorder.bus)

        (tab to recorder.sent) shouldBe
            (RecentsTab to listOf(resultKey<RecentsModeEvent>() to RecentsModeEvent(RecentsMode.HISTORY)))
    }

    @Test
    fun `without the combined tab a recents shortcut names no mode, since nothing would read it`() {
        val tabs = listOf(TabEvent.Updates, TabEvent.History).map { HomeScreen.tabFor(it, false, recorder.bus) }

        (tabs to recorder.sent) shouldBe (listOf(UpdatesTab, HistoryTab) to emptyList<Pair<String, Any?>>())
    }
}
