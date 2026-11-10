package reikai.domain.local

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

/** One lock per local series: a listing waits for the replay at start, and for a rename of the same folder. */
class LocalSeriesLocksTest {

    @Test
    fun `a listing waits until the journal replay has opened the locks`() = runTest {
        val locks = LocalSeriesLocks()

        val listing = async { locks.withSeries(ContentType.MANGA, "Saga") { "listed" } }
        repeat(3) { yield() }
        val beforeOpen = listing.isCompleted
        locks.open()

        (beforeOpen to listing.await()) shouldBe (false to "listed")
    }

    @Test
    fun `the same folder in another case waits for the holder, another folder does not`() = runTest {
        val locks = LocalSeriesLocks().apply { open() }
        val held = CompletableDeferred<Unit>()
        launch { locks.withSeries(ContentType.MANGA, "Saga") { held.await() } }
        repeat(3) { yield() }

        val sameFolder = async { locks.withSeries(ContentType.MANGA, "saga") { "same" } }
        val otherFolder = async { locks.withSeries(ContentType.MANGA, "Other") { "other" } }
        val otherType = async { locks.withSeries(ContentType.NOVELS, "Saga") { "novel" } }
        repeat(3) { yield() }
        val whileHeld = listOf(sameFolder, otherFolder, otherType).map { it.isCompleted }
        held.complete(Unit)

        (whileHeld to sameFolder.await()) shouldBe (listOf(false, true, true) to "same")
    }

    @Test
    fun `the replay's own lock does not wait for the opening it brings`() = runTest {
        val locks = LocalSeriesLocks()

        locks.locked(ContentType.NOVELS, "Saga") { "replayed" } shouldBe "replayed"
    }
}
