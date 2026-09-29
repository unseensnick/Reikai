package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.FavoritedNovels
import reikai.domain.novel.model.Novel
import reikai.novel.host.NovelItem
import reikai.presentation.browse.catalogue.EntryBrowseRow
import tachiyomi.domain.manga.model.Manga

/**
 * The result rows a search and the feed hold, pinned once for both content types. A source answers
 * with what it had when it was asked, so a row has to follow the library, or its badge and a long
 * press disagree about an entry added since; and it may only follow while drawn, since a result row
 * has no scope that ends when it scrolls away.
 */
class SearchResultRowsConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a row reads as in the library once its entry is added`(probe: ResultRowProbe) = runTest {
        val row = probe.row()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { row.content.collect {} }

        probe.addToLibrary()

        row.content.value.ui.favorite shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a row follows the library only while something draws it`(probe: ResultRowProbe) = runTest {
        val row = probe.row()
        val drawing = launch(UnconfinedTestDispatcher(testScheduler)) { row.content.collect {} }
        val whileDrawn = probe.followers()

        drawing.cancelAndJoin()

        (whileDrawn to probe.followers()) shouldBe (1 to 0)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaResultRowProbe(), NovelResultRowProbe())
    }
}

/** One content type's result row over a library the test can change. */
interface ResultRowProbe {
    fun row(): EntryBrowseRow
    fun addToLibrary()

    /** How many collectors the row holds on the library right now. */
    fun followers(): Int
}

class MangaResultRowProbe : ResultRowProbe {
    private val listed = Manga.create().copy(id = 1L, url = "/1", title = "listed")
    private val stored = MutableStateFlow<Manga?>(listed)

    override fun toString() = "manga"
    override fun row() = liveMangaRow(listed, stored)
    override fun addToLibrary() {
        stored.value = listed.copy(favoriteAt = 1L)
    }
    override fun followers() = stored.subscriptionCount.value
}

class NovelResultRowProbe : ResultRowProbe {
    private val item = NovelItem(name = "listed", path = "/listed", cover = null)
    private val favorited = MutableStateFlow(FavoritedNovels.None)

    override fun toString() = "novel"
    override fun row() = novelBrowseRow(item, SOURCE_ID, favorited)
    override fun addToLibrary() {
        favorited.value = FavoritedNovels.of(
            listOf(Novel.create().copy(source = SOURCE_ID, url = item.path, favoriteAt = 1L)),
        )
    }
    override fun followers() = favorited.subscriptionCount.value

    private companion object {
        const val SOURCE_ID = "src"
    }
}
