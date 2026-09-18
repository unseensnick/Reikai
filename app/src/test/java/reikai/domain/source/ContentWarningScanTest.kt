package reikai.domain.source

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentWarningScanTest {

    private val allWarnings = ContentWarningScan(
        enabled = setOf(ContentWarning.SAFE, ContentWarning.MIXED, ContentWarning.NSFW),
        applyToInstalled = true,
    )
    private val safeOnly = allWarnings.copy(enabled = setOf(ContentWarning.SAFE))

    @Test
    fun `a warning set written during the scan triggers a reload`() = runTest {
        val settings = MutableStateFlow(safeOnly)
        var reloads = 0

        val job = launch { settings.reloadWhenScanStale({ allWarnings }) { reloads++ } }
        advanceUntilIdle()
        job.cancel()

        reloads shouldBe 1
    }

    @Test
    fun `settings matching the scan do not reload`() = runTest {
        val settings = MutableStateFlow(allWarnings)
        var reloads = 0

        val job = launch { settings.reloadWhenScanStale({ allWarnings }) { reloads++ } }
        advanceUntilIdle()
        job.cancel()

        reloads shouldBe 0
    }

    @Test
    fun `a toggle after the scan reloads`() = runTest {
        val settings = MutableStateFlow(allWarnings)
        var scanned = allWarnings
        var reloads = 0

        val job = launch {
            settings.reloadWhenScanStale({ scanned }) {
                scanned = settings.value
                reloads++
            }
        }
        advanceUntilIdle()
        settings.value = allWarnings.copy(applyToInstalled = false)
        advanceUntilIdle()
        job.cancel()

        reloads shouldBe 1
    }
}
