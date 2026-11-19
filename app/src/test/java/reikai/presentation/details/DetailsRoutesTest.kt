package reikai.presentation.details

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import reikai.presentation.novel.details.NovelScreen
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
        val screen = EntryId.Manga(3L).detailsScreen(fromSource = true) as MangaScreen

        (screen.mangaId to screen.fromSource) shouldBe (3L to true)
    }

    @Test
    fun `a novel id opens the novel page for that id`() {
        val screen = EntryId.Novel(7L).detailsScreen() as NovelScreen

        (screen.novelId to screen.fromSource) shouldBe (7L to false)
    }

    @Test
    fun `a manga result opens its stored manga as a listing opens it`() = runTest {
        val row = mangaBrowseRow(MutableStateFlow(Manga.create().copy(id = 5L, url = "/m/5") to null))

        val screen = row.detailsScreen(SourceKey.Manga(1L), novels) as MangaScreen

        (screen.mangaId to screen.fromSource) shouldBe (5L to true)
    }

    @Test
    fun `a novel result is stored and then opened by its row id`() = runTest {
        val screen = novelRow(listed).detailsScreen(SourceKey.Novel("plugin"), novels) as NovelScreen

        (screen.novelId to screen.fromSource) shouldBe (9L to true)
    }

    @Test
    fun `a novel result whose row cannot be written opens nothing`() = runTest {
        novelRow(unwritable).detailsScreen(SourceKey.Novel("plugin"), novels) shouldBe null
    }

    // Voyager pushes whatever it is handed, so a double tap would stack the page twice.
    @Test
    fun `a second tap while the first page is still being built opens nothing`() = runTest {
        val pushed = mutableListOf<Screen>()
        val opener = DetailsOpener(this, pushed::add, { novels }) {}
        val first = NovelScreen(1L)
        val building = CompletableDeferred<Screen?>()

        opener.open { building.await() }
        opener.open { NovelScreen(2L) }
        building.complete(first)
        advanceUntilIdle()

        pushed shouldBe listOf(first)
    }

    @Test
    fun `a page that cannot be built says so`() = runTest {
        var failures = 0
        val opener = DetailsOpener(this, {}, { novels }) { failures++ }

        opener.open { null }
        advanceUntilIdle()

        failures shouldBe 1
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shownCases")
    fun `a details page shows only its own entry`(
        @Suppress("UNUSED_PARAMETER") name: String,
        screen: Screen,
        entry: EntryId,
        shows: Boolean,
    ) {
        screen.shows(entry) shouldBe shows
    }

    companion object {
        @JvmStatic
        fun shownCases() = listOf(
            arrayOf("manga page, its id", MangaScreen(1L), EntryId.Manga(1L), true),
            arrayOf("manga page, another id", MangaScreen(2L), EntryId.Manga(1L), false),
            arrayOf("manga page, a novel of the same id", MangaScreen(1L), EntryId.Novel(1L), false),
            arrayOf("novel page, its id", NovelScreen(1L), EntryId.Novel(1L), true),
            arrayOf("novel page, another id", NovelScreen(2L), EntryId.Novel(1L), false),
            arrayOf("novel page, a manga of the same id", NovelScreen(1L), EntryId.Manga(1L), false),
        )
    }
}
