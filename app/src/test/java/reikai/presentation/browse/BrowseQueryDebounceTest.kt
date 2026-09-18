package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseQueryDebounceTest {

    @Test
    fun `an empty search reaches the list without waiting`() = runTest {
        val query = MutableStateFlow<String?>(null)
        val seen = mutableListOf<String?>()

        val job = launch { query.debouncedBrowseQuery().collect { seen += it } }
        advanceTimeBy(1)
        job.cancel()

        seen shouldBe listOf(null)
    }

    @Test
    fun `typing still waits for the pause`() = runTest {
        val query = MutableStateFlow<String?>("nov")
        val seen = mutableListOf<String?>()

        val job = launch { query.debouncedBrowseQuery().collect { seen += it } }
        advanceTimeBy(BROWSE_SEARCH_DEBOUNCE.inWholeMilliseconds - 1)
        job.cancel()

        seen shouldBe emptyList()
    }
}
