package reikai.presentation.track

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun TestScope.useTestDispatchers() {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        mockIoDispatcher(dispatcher)
    }

    private fun model(port: EntryTrackPort) = EntryTrackerSearchRoute.Model(
        entry = EntryId.Manga(1L),
        currentUrl = null,
        initialQuery = "",
        trackerId = TRACKER_ID,
        appScope = testAppScope(),
        context = mockk(),
        trackerManager = mockk { every { get(TRACKER_ID) } returns mockk(relaxed = true) },
        ports = mockk { every { of(any()) } returns port },
    )

    private companion object {
        const val TRACKER_ID = 1L
    }
}
