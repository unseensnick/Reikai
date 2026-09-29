package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.novel.host.NovelItem
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import kotlin.time.Duration.Companion.seconds

/**
 * A bulk add decides what is already in the library when it runs, not when the entry was selected.
 * A list can be drawn long before the add, so an entry added since would otherwise be favorited again
 * and moved into the batch's category, and one removed since would be skipped.
 */
class BulkAddConformanceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry added since it was selected is skipped`(probe: BulkAddProbe) = runTest {
        probe.addSelection(listedFavorite = false, storedFavorite = true) shouldBe
            BulkAddEffects(favoriteWritten = false, filed = listOf(STORED_CATEGORY))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry removed since it was selected is added`(probe: BulkAddProbe) = runTest {
        probe.addSelection(listedFavorite = true, storedFavorite = false) shouldBe
            BulkAddEffects(favoriteWritten = true, filed = listOf(DEFAULT_CATEGORY))
    }

    companion object {
        const val DEFAULT_CATEGORY = 3L
        const val STORED_CATEGORY = 5L

        @JvmStatic
        fun probes() = listOf(MangaBulkAddProbe(), NovelBulkAddProbe())
    }
}

data class BulkAddEffects(val favoriteWritten: Boolean, val filed: List<Long>)

/** One content type's bulk facade over its fake library, with a default category set so nothing asks. */
interface BulkAddProbe {
    /** Selects one entry as a list drew it, then adds the selection once the library says [storedFavorite]. */
    suspend fun addSelection(listedFavorite: Boolean, storedFavorite: Boolean): BulkAddEffects
}

// The facade adds on the IO dispatcher, so the wait runs in real time rather than the test clock.
private suspend fun <T> settle(block: suspend () -> T): T =
    withContext(Dispatchers.Default) { withTimeout(5.seconds) { block() } }

private fun storedCategories(storedFavorite: Boolean) =
    if (storedFavorite) listOf(BulkAddConformanceTest.STORED_CATEGORY) else emptyList()

class MangaBulkAddProbe : BulkAddProbe {
    override fun toString() = "manga"

    override suspend fun addSelection(listedFavorite: Boolean, storedFavorite: Boolean): BulkAddEffects {
        val library = FakeMangaLibrary(
            userCategories = listOf(libraryCategory(BulkAddConformanceTest.DEFAULT_CATEGORY)),
            defaultCategoryId = BulkAddConformanceTest.DEFAULT_CATEGORY.toInt(),
        )
        val listed = library.put(1L, listedFavorite)
        library.put(1L, storedFavorite, storedCategories(storedFavorite))
        val model = BulkFavoriteViewModel(library.adder, library.libraryPreferences)

        model.select(listed)
        model.addFavorite()
        settle { model.state.first { !it.selectionMode } }

        return BulkAddEffects(library.favoriteWrites.isNotEmpty(), library.filed[1L].orEmpty())
    }
}

class NovelBulkAddProbe : BulkAddProbe {
    override fun toString() = "novel"

    override suspend fun addSelection(listedFavorite: Boolean, storedFavorite: Boolean): BulkAddEffects {
        val library = FakeNovelLibrary(
            userCategories = listOf(libraryCategory(BulkAddConformanceTest.DEFAULT_CATEGORY)),
            defaultCategoryId = BulkAddConformanceTest.DEFAULT_CATEGORY.toInt(),
        )
        // A browsed novel carries no library state of its own, so what the list drew has nowhere to live.
        library.put(1L, "/a", storedFavorite, storedCategories(storedFavorite))
        val model = NovelBulkFavoriteViewModel(library.adder, library.novelPreferences)

        model.toggleSelection(FakeNovelLibrary.SOURCE_ID, NovelItem(name = "a", path = "/a", cover = null))
        model.addFavorite()
        settle { model.state.first { !it.selectionMode } }

        return BulkAddEffects(library.favoriteWrites.isNotEmpty(), library.filed[1L].orEmpty())
    }
}
