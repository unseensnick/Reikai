package reikai.presentation.details

import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.tachiyomi.ui.home.TabEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import mihon.core.navigation.HomeRoute
import mihon.core.navigation.MangaNotesRoute
import mihon.core.navigation.Navigator
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.OtherTestRoute
import reikai.presentation.RecordingResultBus
import reikai.presentation.browse.catalogue.BrowseSearchEvent
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.browse.globalsearch.EntryGlobalSearchRoute
import reikai.presentation.migrate.flow.EntryMigrationSourcePickRoute
import reikai.presentation.novel.notes.NovelNotesRoute
import reikai.presentation.resultKey
import reikai.presentation.testNavigator
import reikai.presentation.track.EntryTrackSheetRoute

/** The details page's neutral pushes land on the same destination for a manga and a novel. */
class DetailsPushesConformanceTest {

    // A merged series viewed on a member's chip, so the anchor and the viewed member differ.
    class Case(val anchor: EntryId, val viewed: Long, val source: SourceKey) {
        override fun toString() = anchor.contentType.name
    }

    private val recorder = RecordingResultBus()

    @ParameterizedTest
    @MethodSource("cases")
    fun `a global search is scoped to the entry's type`(case: Case) = pushes(case) { nav, stack ->
        nav.onGlobalSearch("query")

        val top = stack.current.shouldBeInstanceOf<EntryGlobalSearchRoute>()
        (top.searchQuery to top.scopedContentType) shouldBe ("query" to case.anchor.contentType)
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a library search searches the library for the entry's type`(case: Case) = pushes(case) { nav, _ ->
        nav.onLibrarySearch("query")

        recorder.sent shouldBe listOf(resultKey<TabEvent>() to librarySearch("query", case))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a genre returns to the viewed source's catalogue`(case: Case) {
        val catalogue = EntryCatalogueRoute(case.source)
        pushes(case, catalogue) { nav, stack ->
            nav.onTagSearch("Action")

            (stack.current to recorder.sent) shouldBe
                (catalogue to listOf(resultKey<BrowseSearchEvent>() to BrowseSearchEvent("Action", genre = true)))
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a genre on a source with no filters searches its catalogue for it as text`(case: Case) {
        val catalogue = EntryCatalogueRoute(case.source)
        pushes(case, catalogue, filterable = false) { nav, _ ->
            nav.onTagSearch("Action")

            recorder.sent shouldBe listOf(resultKey<BrowseSearchEvent>() to BrowseSearchEvent("Action", genre = false))
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a genre with no catalogue behind it searches the library for the entry's type`(case: Case) =
        pushes(case) { nav, _ ->
            nav.onTagSearch("Action")

            recorder.sent shouldBe listOf(resultKey<TabEvent>() to librarySearch("Action", case))
        }

    @ParameterizedTest
    @MethodSource("cases")
    fun `the source row browses the viewed source`(case: Case) = pushes(case) { nav, stack ->
        nav.onBrowseSource!!()

        stack.current.shouldBeInstanceOf<EntryCatalogueRoute>().sourceKey shouldBe case.source
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a source with nothing to browse offers no source row tap`(case: Case) =
        pushes(case, browsable = false) { nav, _ ->
            nav.onBrowseSource shouldBe null
        }

    @ParameterizedTest
    @MethodSource("cases")
    fun `the tracker settings push opens tracker settings`(case: Case) = pushes(case) { nav, stack ->
        nav.onOpenTrackingSettings()

        stack.current.shouldBeInstanceOf<SettingsTrackingScreen>()
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `the track sheet push opens the sheet for the anchor`(case: Case) = pushes(case) { nav, stack ->
        nav.onOpenTrackSheet()

        stack.current.shouldBeInstanceOf<EntryTrackSheetRoute>().entry shouldBe case.anchor
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `Migrate picks sources for the anchor`(case: Case) = pushes(case) { nav, stack ->
        nav.onMigrate!!()

        val top = stack.current.shouldBeInstanceOf<EntryMigrationSourcePickRoute>()
        (top.contentType to top.entryIds) shouldBe (case.anchor.contentType to listOf(case.anchor.rawId))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `notes open the viewed member's own`(case: Case) = pushes(case) { nav, stack ->
        nav.onEditNotes()

        when (val top = stack.current) {
            is MangaNotesRoute -> EntryId.Manga(top.mangaId)
            is NovelNotesRoute -> EntryId.Novel(top.novelId)
            else -> top
        } shouldBe EntryId.of(case.anchor.contentType, case.viewed)
    }

    private fun pushes(
        case: Case,
        vararg below: NavKey,
        browsable: Boolean = true,
        filterable: Boolean = true,
        test: (SharedDetailsPushes, Navigator) -> Unit,
    ) {
        val stack = testNavigator(HomeRoute, *below, OtherTestRoute)
        val nav = EntryDetailsNavigation(
            navigateUp = {},
            onOpenChapter = {},
            viewedSource = DetailsViewedSource(case.source, browsable = browsable, filterable = filterable),
            onOpenFilterSettings = {},
        ).withSharedPushes(
            loadedDetails().copy(entryId = case.anchor, viewedEntryId = case.viewed),
            stack,
            recorder.bus,
            twoPane = false,
        )
        test(nav, stack)
    }

    private fun librarySearch(query: String, case: Case) =
        TabEvent.Library(query = query, contentType = case.anchor.contentType)

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Case(EntryId.Manga(100L), viewed = 101L, source = SourceKey.Manga(7L)),
            Case(EntryId.Novel(100L), viewed = 101L, source = SourceKey.Novel("plugin")),
        )
    }
}
