package eu.kanade.tachiyomi.ui.home

import androidx.navigation3.runtime.NavKey
import eu.kanade.tachiyomi.ui.browse.BrowseShowExtensionsEvent
import eu.kanade.tachiyomi.ui.library.LibrarySearchEvent
import io.kotest.matchers.shouldBe
import mihon.core.navigation.DownloadQueueRoute
import mihon.core.navigation.MangaRoute
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.RecordingResultBus
import reikai.presentation.novel.details.NovelRoute
import reikai.presentation.recents.RecentsMode
import reikai.presentation.recents.RecentsModeEvent
import reikai.presentation.resultKey

/** A tab event opens its tab and sends on what it asks of that tab, to that tab's own collector. */
class HomeTabEventsTest {

    private val recorder = RecordingResultBus()

    @Test
    fun `a library search opens the library and searches it on the type's chip`() {
        val tab = tabFor(
            TabEvent.Library(query = "q", contentType = ContentType.NOVELS),
            false,
            recorder.bus,
        )

        (tab to recorder.sent) shouldBe
            (
                TopLevelRoute.Library to
                    listOf(resultKey<LibrarySearchEvent>() to LibrarySearchEvent("q", ContentType.NOVELS))
                )
    }

    @Test
    fun `opening the library alone searches nothing`() {
        val tab = tabFor(TabEvent.Library(), false, recorder.bus)

        (tab to recorder.sent) shouldBe (TopLevelRoute.Library to emptyList<Pair<String, Any?>>())
    }

    @Test
    fun `the extensions shortcut opens Browse on Extensions`() {
        val tab = tabFor(TabEvent.Browse(toExtensions = true), false, recorder.bus)

        (tab to recorder.sent) shouldBe
            (TopLevelRoute.Browse to listOf(resultKey<BrowseShowExtensionsEvent>() to BrowseShowExtensionsEvent))
    }

    @Test
    fun `a recents shortcut opens the combined tab on its mode`() {
        val tab = tabFor(TabEvent.History, true, recorder.bus)

        (tab to recorder.sent) shouldBe
            (RecentsTabRoute to listOf(resultKey<RecentsModeEvent>() to RecentsModeEvent(RecentsMode.HISTORY)))
    }

    @Test
    fun `without the combined tab a recents shortcut names no mode, since nothing would read it`() {
        val tabs = listOf(TabEvent.Updates, TabEvent.History).map { tabFor(it, false, recorder.bus) }

        (tabs to recorder.sent) shouldBe
            (listOf(TopLevelRoute.Updates, TopLevelRoute.History) to emptyList<Pair<String, Any?>>())
    }

    @ParameterizedTest(name = "{0} with combined {1}, stats {2} goes to {3}")
    @MethodSource("replacements")
    fun `a tab that left the set is replaced by the tab that took its place`(
        tab: TopLevelRoute,
        combinedRecents: Boolean,
        statsTab: Boolean,
        replacement: TopLevelRoute?,
    ) {
        replacementFor(tab, combinedRecents, statsTab) shouldBe replacement
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pages")
    fun `a tab event opens the page it asks for over its tab`(event: TabEvent, page: NavKey?) {
        pageFor(event) shouldBe page
    }

    @Test
    fun `the combined tab drops the two feeds and a hidden Statistics tab`() {
        val shown = listOf(TopLevelRoute.Library, RecentsTabRoute, TopLevelRoute.Browse, TopLevelRoute.More)

        droppedTabs(shown).toSet() shouldBe setOf(TopLevelRoute.Updates, TopLevelRoute.History, StatsTabRoute)
    }

    @Test
    fun `the two feeds drop the combined tab`() {
        val shown = listOf(
            TopLevelRoute.Library,
            TopLevelRoute.Updates,
            TopLevelRoute.History,
            TopLevelRoute.Browse,
            StatsTabRoute,
            TopLevelRoute.More,
        )

        droppedTabs(shown) shouldBe listOf(RecentsTabRoute)
    }

    companion object {
        @JvmStatic
        fun replacements() = listOf(
            arrayOf(TopLevelRoute.Updates, true, true, RecentsTabRoute),
            arrayOf(TopLevelRoute.History, true, false, RecentsTabRoute),
            arrayOf(TopLevelRoute.Updates, false, true, null),
            arrayOf(RecentsTabRoute, false, true, TopLevelRoute.Updates),
            arrayOf(RecentsTabRoute, true, true, null),
            arrayOf(StatsTabRoute, true, false, TopLevelRoute.More),
            arrayOf(StatsTabRoute, false, true, null),
            arrayOf(TopLevelRoute.Library, false, false, null),
        )

        @JvmStatic
        fun pages() = listOf(
            arrayOf(TabEvent.Library(EntryId.Manga(1L)), MangaRoute(1L)),
            arrayOf(TabEvent.Library(EntryId.Novel(2L)), NovelRoute(2L)),
            arrayOf(TabEvent.Library(query = "q"), null),
            arrayOf(TabEvent.More(toDownloads = true), DownloadQueueRoute),
            arrayOf(TabEvent.More(toDownloads = false), null),
            arrayOf(TabEvent.Updates, null),
        )
    }
}
