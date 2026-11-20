package reikai.presentation.track

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.track.EntryTrackPort
import reikai.util.mockIoDispatcher
import reikai.util.testAppScope

/** The tracker search one model serves for both content types (mihonapp/mihon#4071). */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryTrackerSearchModelTest {

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `a late result from an earlier search does not replace the newer one`() = runTest {
        useTestDispatchers()
        val older = CompletableDeferred<List<TrackSearch>>()
        val newer = listOf(mockk<TrackSearch>(relaxed = true))
        val model = model(
            mockk {
                every { tracks() } returns flowOf(emptyList())
                coEvery { title() } returns ""
                coEvery { search(any(), "old") } coAnswers { older.await() }
                coEvery { search(any(), "new") } returns newer
            },
        )
        model.trackingSearch("old")
        advanceUntilIdle()
        model.trackingSearch("new")
        advanceUntilIdle()

        older.complete(listOf(mockk(relaxed = true)))
        advanceUntilIdle()

        model.state.value.queryResult shouldBe Result.success(newer)
    }

    @Test
    fun `an unbound tracker's search starts from the entry's title read by id`() = runTest {
        useTestDispatchers()
        val model = model(
            mockk {
                every { tracks() } returns flowOf(emptyList())
                coEvery { title() } returns "Edited title"
                coEvery { search(any(), any()) } returns emptyList()
            },
        )
        advanceUntilIdle()

        model.query.text.toString() shouldBe "Edited title"
    }

    @Test
    fun `a query typed before the title is read is not replaced by it`() = runTest {
        useTestDispatchers()
        val title = CompletableDeferred<String>()
        val model = model(
            mockk {
                every { tracks() } returns flowOf(emptyList())
                coEvery { title() } coAnswers { title.await() }
            },
        )
        advanceUntilIdle()
        model.query.setTextAndPlaceCursorAtEnd("typed")

        title.complete("Edited title")
        advanceUntilIdle()

        model.query.text.toString() shouldBe "typed"
    }

    @Test
    fun `a pick awaiting the replace confirmation is held by the model`() = runTest {
        useTestDispatchers()
        val model = model(
            mockk {
                every { tracks() } returns flowOf(emptyList())
                coEvery { title() } returns ""
            },
        )
        val pick = mockk<TrackSearch>(relaxed = true)

        model.askToReplace(pick)

        model.state.value.pendingBind shouldBe pick
    }

    private fun TestScope.useTestDispatchers() {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        mockIoDispatcher(dispatcher)
    }

    private fun model(port: EntryTrackPort) = EntryTrackerSearchRoute.Model(
        entry = EntryId.Manga(1L),
        trackerId = TRACKER_ID,
        savedState = SavedStateHandle(),
        appScope = testAppScope(),
        context = mockk(),
        trackerManager = mockk { every { get(TRACKER_ID) } returns mockk(relaxed = true) },
        ports = mockk { every { of(any()) } returns port },
    )

    private companion object {
        const val TRACKER_ID = 1L
    }
}
