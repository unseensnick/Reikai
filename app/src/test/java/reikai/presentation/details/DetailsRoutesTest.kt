package reikai.presentation.details

import androidx.navigation3.runtime.NavKey
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.core.navigation.MangaRoute
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.novel.FavoritedNovels
import reikai.domain.novel.model.Novel
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.presentation.browse.mangaBrowseRow
import reikai.presentation.browse.novelBrowseRow
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.presentation.novel.details.NovelRoute
import tachiyomi.domain.manga.model.Manga

class DetailsRoutesTest {

    private val listed = NovelItem("Title", "/n/5", "cover")
    private val unwritable = NovelItem("Other", "/n/6", null)

    // The row "/n/5" stores as id 9; "/n/6" cannot be written.
    private val novels = mockk<NovelLibraryAdder> {
        coEvery { materialize(listed, "plugin") } returns Novel.create().copy(id = 9L, source = "plugin", url = "/n/5")
        coEvery { materialize(unwritable, "plugin") } returns null
    }

    private fun novelRow(item: NovelItem) = novelBrowseRow(item, "plugin", MutableStateFlow(FavoritedNovels.None))

    @Test
    fun `a manga id opens the manga page for that id`() {
        val screen = EntryId.Manga(3L).detailsRoute(fromSource = true) as MangaRoute

        (screen.mangaId to screen.fromSource) shouldBe (3L to true)
    }

    @Test
    fun `a novel id opens the novel page for that id`() {
        val screen = EntryId.Novel(7L).detailsRoute() as NovelRoute

        (screen.novelId to screen.fromSource) shouldBe (7L to false)
    }

    @Test
    fun `a manga result opens its stored manga as a listing opens it`() = runTest {
        val row = mangaBrowseRow(MutableStateFlow(Manga.create().copy(id = 5L, url = "/m/5") to null))

        val screen = row.detailsRoute(SourceKey.Manga(1L), novels) as MangaRoute

        (screen.mangaId to screen.fromSource) shouldBe (5L to true)
    }

    @Test
    fun `a novel result is stored and then opened by its row id`() = runTest {
        val screen = novelRow(listed).detailsRoute(SourceKey.Novel("plugin"), novels) as NovelRoute

        (screen.novelId to screen.fromSource) shouldBe (9L to true)
    }

    @Test
    fun `a novel result whose row cannot be written opens nothing`() = runTest {
        novelRow(unwritable).detailsRoute(SourceKey.Novel("plugin"), novels) shouldBe null
    }

    // A second tap lands after the first page opened, so it would stack another page over it.
    @Test
    fun `a second tap while the first page is still being built opens nothing`() = runTest {
        val pushed = mutableListOf<NavKey>()
        val opener = DetailsOpener(this, pushed::add, { novels }) {}
        val first = NovelRoute(1L)
        val building = CompletableDeferred<NavKey?>()

        opener.open { building.await() }
        opener.open { NovelRoute(2L) }
        building.complete(first)
        advanceUntilIdle()

        pushed shouldBe listOf(first)
    }

    @Test
    fun `a tap once the first page has opened opens its own`() = runTest {
        val pushed = mutableListOf<NavKey>()
        val opener = DetailsOpener(this, pushed::add, { novels }) {}

        opener.open { NovelRoute(1L) }
        advanceUntilIdle()
        opener.open { NovelRoute(2L) }
        advanceUntilIdle()

        pushed shouldBe listOf(NovelRoute(1L), NovelRoute(2L))
    }

    @Test
    fun `a page that cannot be built says so`() = runTest {
        var failures = 0
        val opener = DetailsOpener(this, {}, { novels }) { failures++ }

        opener.open { null }
        advanceUntilIdle()

        failures shouldBe 1
    }

    // Global search's lone result replaces the search with its page rather than pushing over it.
    @Test
    fun `a page opened for no tap goes where it is sent`() = runTest {
        val sent = mutableListOf<NavKey>()
        val opener = DetailsOpener(this, { error("pushed") }, { novels }) { error("failed") }

        opener.open(navigate = sent::add) { EntryId.Novel(9L).detailsRoute() }
        advanceUntilIdle()

        sent.map { (it as NovelRoute).novelId } shouldBe listOf(9L)
    }

    @Test
    fun `a page opened for no tap that cannot be built says so and hands back`() = runTest {
        var failures = 0
        var handedBack = 0
        val opener = DetailsOpener(this, {}, { novels }) { failures++ }

        opener.open(navigate = {}, afterFailure = { handedBack++ }) { null }
        advanceUntilIdle()

        (failures to handedBack) shouldBe (1 to 1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shownCases")
    fun `a details page shows only its own entry`(
        @Suppress("UNUSED_PARAMETER") name: String,
        screen: NavKey,
        entry: EntryId,
        shows: Boolean,
    ) {
        screen.shows(entry) shouldBe shows
    }

    companion object {
        @JvmStatic
        fun shownCases() = listOf(
            arrayOf("manga page, its id", MangaRoute(1L), EntryId.Manga(1L), true),
            arrayOf("manga page, another id", MangaRoute(2L), EntryId.Manga(1L), false),
            arrayOf("manga page, a novel of the same id", MangaRoute(1L), EntryId.Novel(1L), false),
            arrayOf("novel page, its id", NovelRoute(1L), EntryId.Novel(1L), true),
            arrayOf("novel page, another id", NovelRoute(2L), EntryId.Novel(1L), false),
            arrayOf("novel page, a manga of the same id", NovelRoute(1L), EntryId.Manga(1L), false),
        )
    }
}
