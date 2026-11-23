package reikai.presentation.browse

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.novel.host.NovelItem
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

/**
 * A bulk add decides what is already in the library when it runs, not when the entry was selected.
 * A list can be drawn long before the add, so an entry added since would otherwise be favorited again
 * and moved into the batch's category, and one removed since would be skipped.
 */
class BulkAddConformanceTest {

    // The facade adds on IO and its app scope runs there too, so both go on the test scheduler.
    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a batch still files an entry after its screen closes mid-add`(probe: BulkAddProbe) = runTest {
        probe.addAcrossClose() shouldBe listOf(DEFAULT_CATEGORY)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an add that fails after its screen closes reaches the app scope's handler`(probe: BulkAddProbe) = runTest {
        probe.failureAcrossClose()?.message shouldBe FILING_FAILED
    }

    companion object {
        const val DEFAULT_CATEGORY = 3L
        const val STORED_CATEGORY = 5L
        const val FILING_FAILED = "filing failed"

        @JvmStatic
        fun probes() = listOf(MangaBulkAddProbe(), NovelBulkAddProbe())
    }
}

data class BulkAddEffects(val favoriteWritten: Boolean, val filed: List<Long>)

/** One content type's bulk facade over its fake library, with a default category set so nothing asks. */
interface BulkAddProbe {
    /** Selects one entry as a list drew it, then adds the selection once the library says [storedFavorite]. */
    suspend fun addSelection(listedFavorite: Boolean, storedFavorite: Boolean): BulkAddEffects

    /** Adds one entry, closes the screen once it is favorited but before it is filed, and answers its categories. */
    suspend fun addAcrossClose(): List<Long>

    /** As [addAcrossClose], with the filing failing once the screen is gone: what the app scope's handler got. */
    suspend fun failureAcrossClose(): Throwable?
}

/** Adds [model]'s selection, closes its screen while [holdFiling] holds the add, then lets the add go on. */
private suspend fun closeScreenMidFiling(
    model: EntryBulkFavoriteViewModel<*>,
    appScope: CoroutineScope,
    failing: Boolean = false,
    holdFiling: (suspend () -> Unit) -> Unit,
) {
    val held = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    holdFiling {
        held.complete(Unit)
        release.await()
        if (failing) error(BulkAddConformanceTest.FILING_FAILED)
    }
    model.addFavorite()
    held.await()
    model.viewModelScope.cancel()
    release.complete(Unit)
    appScope.coroutineContext.job.children.toList().joinAll()
}

private fun appScope(handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> }) =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

/** Runs [add] over an app scope that records what reaches its handler, as the real one reports a failure. */
private suspend fun reported(add: suspend (CoroutineScope) -> Unit): Throwable? {
    val caught = CompletableDeferred<Throwable>()
    add(appScope(CoroutineExceptionHandler { _, e -> caught.complete(e) }))
    return if (caught.isCompleted) caught.await() else null
}

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
        val model = BulkFavoriteViewModel(library.adder, appScope())

        model.select(listed)
        model.addFavorite()
        model.state.first { !it.selectionMode }

        return BulkAddEffects(library.favoriteWrites.isNotEmpty(), library.filed[1L].orEmpty())
    }

    override suspend fun addAcrossClose(): List<Long> {
        val library = FakeMangaLibrary(
            userCategories = listOf(libraryCategory(BulkAddConformanceTest.DEFAULT_CATEGORY)),
            defaultCategoryId = BulkAddConformanceTest.DEFAULT_CATEGORY.toInt(),
        )
        val appScope = appScope()
        val model = BulkFavoriteViewModel(library.adder, appScope)

        model.select(library.put(1L, favorite = false))
        closeScreenMidFiling(model, appScope) { library.beforeFiling = it }

        return library.filed[1L].orEmpty()
    }

    override suspend fun failureAcrossClose(): Throwable? = reported { appScope ->
        val library = FakeMangaLibrary(
            userCategories = listOf(libraryCategory(BulkAddConformanceTest.DEFAULT_CATEGORY)),
            defaultCategoryId = BulkAddConformanceTest.DEFAULT_CATEGORY.toInt(),
        )
        val model = BulkFavoriteViewModel(library.adder, appScope)

        model.select(library.put(1L, favorite = false))
        closeScreenMidFiling(model, appScope, failing = true) { library.beforeFiling = it }
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
        val model = NovelBulkFavoriteViewModel(library.adder, appScope())

        model.toggleSelection(FakeNovelLibrary.SOURCE_ID, NovelItem(name = "a", path = "/a", cover = null))
        model.addFavorite()
        model.state.first { !it.selectionMode }

        return BulkAddEffects(library.favoriteWrites.isNotEmpty(), library.filed[1L].orEmpty())
    }

    override suspend fun addAcrossClose(): List<Long> {
        val library = FakeNovelLibrary(
            userCategories = listOf(libraryCategory(BulkAddConformanceTest.DEFAULT_CATEGORY)),
            defaultCategoryId = BulkAddConformanceTest.DEFAULT_CATEGORY.toInt(),
        )
        library.put(1L, "/a", favorite = false)
        val appScope = appScope()
        val model = NovelBulkFavoriteViewModel(library.adder, appScope)

        model.toggleSelection(FakeNovelLibrary.SOURCE_ID, NovelItem(name = "a", path = "/a", cover = null))
        closeScreenMidFiling(model, appScope) { library.beforeFiling = it }

        return library.filed[1L].orEmpty()
    }

    override suspend fun failureAcrossClose(): Throwable? = reported { appScope ->
        val library = FakeNovelLibrary(
            userCategories = listOf(libraryCategory(BulkAddConformanceTest.DEFAULT_CATEGORY)),
            defaultCategoryId = BulkAddConformanceTest.DEFAULT_CATEGORY.toInt(),
        )
        library.put(1L, "/a", favorite = false)
        val model = NovelBulkFavoriteViewModel(library.adder, appScope)

        model.toggleSelection(FakeNovelLibrary.SOURCE_ID, NovelItem(name = "a", path = "/a", cover = null))
        closeScreenMidFiling(model, appScope, failing = true) { library.beforeFiling = it }
    }
}
