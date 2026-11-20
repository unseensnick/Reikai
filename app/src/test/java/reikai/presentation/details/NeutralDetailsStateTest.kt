package reikai.presentation.details

import androidx.lifecycle.ViewModel
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.MainDispatcherExtension
import kotlin.time.Duration.Companion.seconds

/** The details adapters' shared state pipeline: never mapped where it is asked for, and stopped with its screen. */
class NeutralDetailsStateTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val source = MutableStateFlow(1)

    private val duplicates = MutableStateFlow(Unit)

    private val model = main.track(object : ViewModel() {})

    private val noVolumes = DetailsVolumeInputs(noVolumePicking(), ContentType.MANGA)

    private fun neutral(value: Int): EntryDetailsScreenState = loadedDetails().copy(
        entryId = EntryId.Manga(value.toLong()),
    )

    private fun mappedOnTheSpot(value: Int): EntryDetailsScreenState = error("mapped where it was asked for")

    // The mapper runs on Dispatchers.Default, off the test clock, so waits run in real time.
    private suspend fun <T> settle(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { block() } }

    /** The mapping walks every chapter row, and asking for the state happens on Main, in composition. */
    @Test
    fun `a first open starts from Loading rather than mapping the model on the spot`() {
        model.neutralDetailsState(source, duplicates, noVolumes) { value, _ -> mappedOnTheSpot(value) }.value shouldBe
            EntryDetailsScreenState.Loading
    }

    /** Each re-entry to the screen asks again, and a second adapter would leave its sharing coroutine behind. */
    @Test
    fun `a screen entered again over the same model gets what it was given the first time`() {
        val first = model.keptForScreen(KEY) { Any() }

        model.keptForScreen(KEY) { Any() } shouldBeSameInstanceAs first
    }

    @Test
    fun `the mapper stops when the last collector leaves`() = runTest {
        val state = model.neutralDetailsState(source, duplicates, noVolumes) { value, _ -> neutral(value) }
        val collector = backgroundScope.launch(Dispatchers.Default) { state.collect {} }
        settle { source.subscriptionCount.first { it == 1 } }

        collector.cancel()

        settle { source.subscriptionCount.first { it == 0 } } shouldBe 0
    }

    /** The copies filter is a pass over every row, so it is built with the rest of the state, off Main. */
    @Test
    fun `Only copies narrows the rows in the state itself`() = runTest {
        val copies = listOf(1L, 2L)
        val rows = loadedDetails(
            items = listOf(
                detailsChapter(1L, "Copy one", 1.0),
                detailsChapter(2L, "Copy two", 1.0),
                detailsChapter(3L, "Other", 2.0),
            ),
            duplicates = DuplicateMarks(copies = copies.associateWith { copies }, marks = emptyMap()),
        )
        val state = model.neutralDetailsState(MutableStateFlow(rows), duplicates, noVolumes) { value, _ -> value }
        val collector = backgroundScope.launch(Dispatchers.Default) { state.collect {} }
        settle { state.first { it is EntryDetailsScreenState.Loaded } }

        noVolumes.setOnlyCopies(true)

        val shown = settle {
            state.first { (it as? EntryDetailsScreenState.Loaded)?.volumeViews?.onlyCopies == true }
        } as EntryDetailsScreenState.Loaded

        shown.volumeViews.items.orEmpty().map { (it as EntryChapterListItem.Chapter).id } shouldBe copies
        collector.cancel()
    }

    private companion object {
        const val KEY = "test"
    }
}
