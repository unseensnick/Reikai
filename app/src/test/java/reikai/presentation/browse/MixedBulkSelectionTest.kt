package reikai.presentation.browse

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.FavoritedNovels
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import tachiyomi.domain.manga.model.Manga

/**
 * The one selection global search and the feed hold over both content types: which picks a verb
 * reaches, and whether the category prompts say which type each one files.
 */
class MixedBulkSelectionTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val collectors = CoroutineScope(dispatcher)
    private val mangaLibrary = FakeMangaLibrary()
    private val novelLibrary = FakeNovelLibrary()
    private lateinit var manga: BulkFavoriteViewModel
    private lateinit var novels: NovelBulkFavoriteViewModel
    private lateinit var selection: MixedBulkSelection

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        manga = BulkFavoriteViewModel(mangaLibrary.adder, mangaLibrary.libraryPreferences)
        novels = NovelBulkFavoriteViewModel(novelLibrary.adder, novelLibrary.novelPreferences)
        selection = MixedBulkSelection(manga, novels, manga.state.asState(), novels.state.asState())
    }

    @AfterEach
    fun tearDown() {
        collectors.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a batch holding both types names each prompt`() {
        selection.toggle(mangaRow(10L), MANGA_SOURCE)
        selection.toggle(novelRow("/a"), NOVEL_SOURCE)

        selection.add()

        selection.namePrompts shouldBe true
    }

    @Test
    fun `a batch holding manga alone leaves its prompt unnamed`() {
        selection.toggle(mangaRow(10L), MANGA_SOURCE)

        selection.add()

        selection.namePrompts shouldBe false
    }

    /** Filing the manga batch empties its selection, which must not unname the novel prompt behind it. */
    @Test
    fun `the second prompt stays named after the first one files its batch`() {
        selection.toggle(mangaRow(10L), MANGA_SOURCE)
        selection.toggle(novelRow("/a"), NOVEL_SOURCE)
        selection.add()

        manga.toggleSelectionMode(false)

        selection.namePrompts shouldBe true
    }

    @Test
    fun `a picked result of either type reads as picked`() {
        val pickedManga = mangaRow(10L)
        val pickedNovel = novelRow("/a")

        selection.toggle(pickedManga, MANGA_SOURCE)
        selection.toggle(pickedNovel, NOVEL_SOURCE)

        selection.selectedKeys shouldBe setOf(pickedManga.key, pickedNovel.key)
    }

    /** Every row takes the set, so a new one per read would redraw them all whenever a source lands. */
    @Test
    fun `the picked keys stay one set until the selection changes`() {
        selection.toggle(mangaRow(10L), MANGA_SOURCE)
        val first = selection.selectedKeys

        selection.selectedKeys shouldBeSameInstanceAs first
    }

    @Test
    fun `select all puts each listed result in its own type's selection`() {
        selection.selectAll(rows(manga = listOf(10L, 11L), novels = listOf("/a")))

        picked() shouldBe (listOf(10L, 11L) to listOf("/a"))
    }

    @Test
    fun `invert flips each type's selection over the listed results`() {
        selection.toggle(mangaRow(10L), MANGA_SOURCE)
        selection.toggle(novelRow("/a"), NOVEL_SOURCE)

        selection.invert(rows(manga = listOf(10L, 11L), novels = listOf("/a", "/b")))

        picked() shouldBe (listOf(11L) to listOf("/b"))
    }

    @Test
    fun `clearing ends the selection for both types`() {
        selection.toggle(mangaRow(10L), MANGA_SOURCE)
        selection.toggle(novelRow("/a"), NOVEL_SOURCE)

        selection.clear()

        selection.selectionMode shouldBe false
    }

    private fun picked() =
        manga.state.value.selection.map { it.id } to novels.state.value.selection.map { it.item.path }

    private fun rows(manga: List<Long>, novels: List<String>) = listOf(
        row(MANGA_SOURCE, manga.map(::mangaRow)),
        row(NOVEL_SOURCE, novels.map(::novelRow)),
    )

    private fun row(key: SourceKey, results: List<EntryBrowseRow>) = BrowseSearchRow(
        key = key,
        name = key.serialize(),
        lang = "en",
        isPinned = false,
        state = EntrySearchState.Success(results),
        source = Unit,
    )

    private fun mangaRow(id: Long) =
        liveMangaRow(Manga.create().copy(id = id, url = "/$id", title = "m$id"), emptyFlow())

    private fun novelRow(path: String) = novelBrowseRow(
        NovelItem(name = "n$path", path = path, cover = null),
        NOVEL_SOURCE.id,
        MutableStateFlow(FavoritedNovels.None),
    )

    // What collectAsState hands the screen: snapshot state kept in step with the flow, so a derived
    // value over it is invalidated the way it is in a composition.
    private fun <T> StateFlow<T>.asState(): State<T> {
        val state = mutableStateOf(value)
        collectors.launch { collect { Snapshot.withMutableSnapshot { state.value = it } } }
        return state
    }

    private companion object {
        val MANGA_SOURCE = SourceKey.Manga(1L)
        val NOVEL_SOURCE = SourceKey.Novel("nb")
    }
}
