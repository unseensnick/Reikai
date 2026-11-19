package reikai.presentation.details

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.novel.FavoritedNovels
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.presentation.browse.mangaBrowseRow
import reikai.presentation.browse.novelBrowseRow
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.domain.manga.model.Manga

class DetailsRoutesTest {

    private val novels = mockk<NovelRepository> {
        coEvery { getById(7L) } returns Novel.create().copy(id = 7L, source = "plugin", url = "/novel/7")
        coEvery { getById(8L) } returns null
    }

    @Test
    fun `a manga id opens the manga page for that id`() = runTest {
        val screen = EntryId.Manga(3L).detailsScreen(novels, fromSource = true) as MangaScreen

        (screen.mangaId to screen.fromSource) shouldBe (3L to true)
    }

    @Test
    fun `a stored novel id opens the page keyed by its source and url`() = runTest {
        val screen = EntryId.Novel(7L).detailsScreen(novels) as NovelScreen

        Triple(screen.sourceId, screen.novelUrl, screen.fromSource) shouldBe Triple("plugin", "/novel/7", false)
    }

    @Test
    fun `a novel id with no stored row opens nothing`() = runTest {
        EntryId.Novel(8L).detailsScreen(novels) shouldBe null
    }

    @Test
    fun `a manga result opens its stored manga as a listing opens it`() {
        val row = mangaBrowseRow(MutableStateFlow(Manga.create().copy(id = 5L, url = "/m/5") to null))

        val screen = row.detailsScreen(SourceKey.Manga(1L)) as MangaScreen

        (screen.mangaId to screen.fromSource) shouldBe (5L to true)
    }

    @Test
    fun `a novel result opens its path on the row's source as a listing opens it`() {
        val row = novelBrowseRow(NovelItem("Title", "/n/5", "cover"), "plugin", MutableStateFlow(FavoritedNovels.None))

        val screen = row.detailsScreen(SourceKey.Novel("plugin")) as NovelScreen

        Triple(screen.sourceId, screen.novelUrl, screen.fromSource) shouldBe Triple("plugin", "/n/5", true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shownCases")
    fun `a details page shows only its own entry`(
        @Suppress("UNUSED_PARAMETER") name: String,
        screen: Screen,
        entry: EntryId,
        shows: Boolean,
    ) {
        screen.shows(entry, SOURCE, URL) shouldBe shows
    }

    companion object {
        private const val SOURCE = "plugin"
        private const val URL = "/novel/1"

        @JvmStatic
        fun shownCases() = listOf(
            arrayOf("manga page, its id", MangaScreen(1L), EntryId.Manga(1L), true),
            arrayOf("manga page, another id", MangaScreen(2L), EntryId.Manga(1L), false),
            arrayOf("manga page, a novel of the same id", MangaScreen(1L), EntryId.Novel(1L), false),
            arrayOf("novel page, its source and url", NovelScreen(SOURCE, URL), EntryId.Novel(1L), true),
            arrayOf("novel page, another url", NovelScreen(SOURCE, "/novel/2"), EntryId.Novel(1L), false),
            arrayOf("novel page, another source", NovelScreen("other", URL), EntryId.Novel(1L), false),
            arrayOf("novel page, a manga", NovelScreen(SOURCE, URL), EntryId.Manga(1L), false),
        )
    }
}
