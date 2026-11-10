package reikai.domain.local

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

/**
 * One lock per local series: a listing waits for the replay at start and for a holder of the same folder; a
 * holder may take the same series again inside its block; two series are taken in one order whatever order
 * they are named in, so two moves between them cannot wait on each other.
 */
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

    @Test
    fun `a holder lists the series it holds without waiting on itself`() = runTest {
        val locks = LocalSeriesLocks().apply { open() }

        val listed = withTimeout(1000) {
            locks.withSeries(ContentType.MANGA, "Saga") {
                locks.withSeries(ContentType.MANGA, "saga") { "listed inside" }
            }
        }

        listed shouldBe "listed inside"
    }

    @Test
    fun `the series a holder took are free again once its block ends`() = runTest {
        val locks = LocalSeriesLocks().apply { open() }
        locks.withSeries(ContentType.MANGA, listOf("Saga", "Other")) { }

        val again = withTimeout(1000) { locks.withSeries(ContentType.MANGA, "Other") { "free" } }

        again shouldBe "free"
    }

    @Test
    fun `two moves naming the same two series in opposite orders both finish`() = runTest {
        val locks = LocalSeriesLocks().apply { open() }
        // A third holder keeps Kept while both moves queue up, so each has taken what it could before the
        // other: in the caller's order the second would hold Other and wait on Kept while the first holds
        // Kept and waits on Other.
        val keptHeld = CompletableDeferred<Unit>()
        launch { locks.withSeries(ContentType.MANGA, "Kept") { keptHeld.await() } }
        repeat(3) { yield() }
        val forward = async { locks.withSeries(ContentType.MANGA, listOf("Kept", "Other")) { "forward" } }
        repeat(3) { yield() }
        val backward = async { locks.withSeries(ContentType.MANGA, listOf("Other", "Kept")) { "backward" } }
        repeat(3) { yield() }
        keptHeld.complete(Unit)

        // Virtual time: two moves waiting on each other leave nothing to run, so the timeout fires at once.
        val finished = withTimeout(1000) { listOf(forward.await(), backward.await()) }

        finished shouldBe listOf("forward", "backward")
    }

    @Test
    fun `a holder asking for a series it did not name up front is refused rather than left to deadlock`() =
        runTest {
            val locks = LocalSeriesLocks().apply { open() }

            shouldThrow<IllegalStateException> {
                locks.withSeries(ContentType.MANGA, "Saga") {
                    locks.withSeries(ContentType.MANGA, "Other") { }
                }
            }
        }

    @Test
    fun `a holder's series are held by its coroutine alone, not by one it starts on another scope`() = runTest {
        val locks = LocalSeriesLocks().apply { open() }
        val holderDone = CompletableDeferred<Unit>()
        val outsider = CompletableDeferred<Boolean>()

        locks.withSeries(ContentType.MANGA, "Saga") {
            backgroundScope.launch {
                val waited = async { locks.withSeries(ContentType.MANGA, "Saga") { true } }
                repeat(3) { yield() }
                outsider.complete(!waited.isCompleted)
                holderDone.complete(Unit)
            }
            holderDone.await()
        }

        outsider.await() shouldBe true
    }
}
