package reikai.presentation.details

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.Stack
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesRoute
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.browse.globalsearch.EntryGlobalSearchScreen
import reikai.presentation.migrate.flow.EntryMigrationSourcePickScreen
import reikai.presentation.novel.notes.NovelNotesScreen

/** The details page's neutral pushes land on the same destination for a manga and a novel. */
class DetailsPushesConformanceTest {

    // A merged series viewed on a member's chip, so the anchor and the viewed member differ.
    class Case(val anchor: EntryId, val viewed: Long, val source: SourceKey) {
        override fun toString() = anchor.contentType.name
    }

    // HomeScreen's search channel has no buffer and nothing collects it here, so it is stubbed.
    @BeforeEach
    fun setUp() {
        mockkObject(HomeScreen)
        coEvery { HomeScreen.search(any(), any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(HomeScreen)
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a global search is scoped to the entry's type`(case: Case) = pushes(case) { nav, stack ->
        nav.onGlobalSearch("query")

        val top = stack.lastItemOrNull.shouldBeInstanceOf<EntryGlobalSearchScreen>()
        (top.searchQuery to top.scopedContentType) shouldBe ("query" to case.anchor.contentType)
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a library search searches the library for the entry's type`(case: Case) = pushes(case) { nav, _ ->
        nav.onLibrarySearch("query")
        advanceUntilIdle()

        coVerify(exactly = 1) { HomeScreen.search("query", case.anchor.contentType) }
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a genre returns to the viewed source's catalogue`(case: Case) {
        val catalogue = mockk<EntryCatalogueScreen> {
            every { sourceKey } returns case.source
            coEvery { searchGenre(any()) } just runs
        }
        pushes(case, catalogue) { nav, _ ->
            nav.onTagSearch("Action")
            advanceUntilIdle()

            coVerify(exactly = 1) { catalogue.searchGenre("Action") }
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a genre with no catalogue behind it searches the library for the entry's type`(case: Case) =
        pushes(case) { nav, _ ->
            nav.onTagSearch("Action")
            advanceUntilIdle()

            coVerify(exactly = 1) { HomeScreen.search("Action", case.anchor.contentType) }
        }

    @ParameterizedTest
    @MethodSource("cases")
    fun `the source row browses the viewed source`(case: Case) = pushes(case) { nav, stack ->
        nav.onBrowseSource!!()

        stack.lastItemOrNull.shouldBeInstanceOf<EntryCatalogueScreen>().sourceKey shouldBe case.source
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a source with nothing to browse offers no source row tap`(case: Case) =
        pushes(case, browsable = false) { nav, _ ->
            nav.onBrowseSource shouldBe null
        }

    @ParameterizedTest
    @MethodSource("cases")
    fun `Tracking with no tracker logged in opens tracker settings`(case: Case) = pushes(case) { nav, stack ->
        nav.onOpenTrackingSettings()

        stack.lastItemOrNull.shouldBeInstanceOf<SettingsScreen>()
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `Migrate picks sources for the anchor`(case: Case) = pushes(case) { nav, stack ->
        nav.onMigrate!!()

        val top = stack.lastItemOrNull.shouldBeInstanceOf<EntryMigrationSourcePickScreen>()
        (top.contentType to top.entryIds) shouldBe (case.anchor.contentType to listOf(case.anchor.rawId))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `notes open the viewed member's own`(case: Case) = pushes(case) { nav, stack ->
        nav.onEditNotes()

        when (val top = stack.lastItemOrNull) {
            is MangaNotesRoute -> EntryId.Manga(top.mangaId)
            is NovelNotesScreen -> EntryId.Novel(top.novelId)
            else -> top
        } shouldBe EntryId.of(case.anchor.contentType, case.viewed)
    }

    private fun pushes(
        case: Case,
        vararg below: Screen,
        browsable: Boolean = true,
        test: suspend TestScope.(EntryDetailsNavigation, Stack<Screen>) -> Unit,
    ) = runTest {
        val stack = mutableStateStackOf(HomeScreen, *below, mockk<Screen>(), minSize = 1)
        val nav = EntryDetailsNavigation(
            navigateUp = {},
            onOpenChapter = {},
            viewedSource = DetailsViewedSource(case.source, browsable = browsable),
            onOpenFilterSettings = {},
        ).withSharedPushes(loadedDetails().copy(entryId = case.anchor, viewedEntryId = case.viewed), stack, this)
        test(nav, stack)
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Case(EntryId.Manga(100L), viewed = 101L, source = SourceKey.Manga(7L)),
            Case(EntryId.Novel(100L), viewed = 101L, source = SourceKey.Novel("plugin")),
        )
    }
}
