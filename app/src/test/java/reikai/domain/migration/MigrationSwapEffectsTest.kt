package reikai.domain.migration

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.manga.MangaGroupCategories
import reikai.domain.track.source.SourceTrackerDispatcher

/** A swap that has committed keeps its follow-up steps, whatever happens to the run around it. */
class MigrationSwapEffectsTest {

    private val sourceTracker = mockk<SourceTrackerDispatcher>(relaxed = true)
    private val from = EntryId.Manga(1L)
    private val to = EntryId.Manga(2L)

    private fun effects(categories: MangaGroupCategories) =
        MigrationSwapEffects(categories, mockk(), mockk(), mockk(), sourceTracker)

    @Test
    fun `a failed category write neither fails the committed swap nor skips the source tracker`() = runTest {
        val categories = mockk<MangaGroupCategories> {
            coEvery { fileMigrationTarget(any(), any(), any()) } throws IllegalStateException("write failed")
        }

        effects(categories).afterSwap(from, to, replace = true, categoryIds = listOf(3L), carriedChapters = false)

        verify { sourceTracker.migrated(from, to, true, false) }
    }

    @Test
    fun `a stop while the target is being filed still files it and tells the source tracker`() = runTest {
        val filing = CompletableDeferred<Unit>()
        var filed = false
        val categories = mockk<MangaGroupCategories> {
            coEvery { fileMigrationTarget(any(), any(), any()) } coAnswers {
                filing.await()
                filed = true
            }
        }
        val run = launch {
            effects(categories).afterSwap(from, to, replace = true, categoryIds = listOf(3L), carriedChapters = false)
        }
        runCurrent()

        run.cancel()
        filing.complete(Unit)
        run.join()

        filed shouldBe true
        verify { sourceTracker.migrated(from, to, true, false) }
    }
}
