package eu.kanade.tachiyomi.ui.browse.extension

import androidx.lifecycle.viewModelScope
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.extension.model.Extensions
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import org.junit.jupiter.api.Test
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

/** What the Extensions list is built from never outlives the screen that watched it. */
class ExtensionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Replaying them would draw "No repos yet" over a store added while the screen was away. */
    @Test
    fun `a model no screen watched past the stop timeout replays no stale answer`() = runTest(dispatcher) {
        val getExtensions = mockk<GetExtensionsByType> {
            every { subscribe(any()) } returns flowOf(Extensions(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        val storeCount = mockk<GetExtensionStoreCountAsFlow> { every { this@mockk.invoke() } returns flowOf(0) }
        virtualDispatchers(dispatcher)
        try {
            val model = ExtensionsViewModel(mockk(relaxed = true), mockk(relaxed = true), getExtensions, storeCount)
            val screen = launch { combine(model.extensions, model.hasRepos) { _, _ -> }.collect {} }
            advanceUntilIdle()
            check(model.extensions.value != null && !model.hasRepos.value)
            screen.cancel()
            advanceUntilIdle()

            model.extensions.value.shouldBeNull()
            model.hasRepos.value shouldBe true
            // Cleared while the test dispatcher is still Main, so nothing resumes on a missing one.
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
        } finally {
            resetVirtualDispatchers()
        }
    }
}
