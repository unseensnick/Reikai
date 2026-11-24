package reikai.domain.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

/** Both readers save progress through this kernel, so its order is the one rule pinning them alike. */
class ReaderProgressWritesTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a slower earlier write still lands before a later one`() = runTest {
        val landed = mutableListOf<Int>()
        val writes = ReaderProgressWrites(this)

        writes.launch {
            delay(100)
            landed += 1
        }
        writes.launch {
            delay(10)
            landed += 2
        }
        advanceUntilIdle()

        landed shouldBe listOf(1, 2)
    }

    /**
     * Production's scope runs on a pool, where two writes launched back to back race for the lock. A
     * dispatcher that runs the later one first stands in for that race, deterministically: only taking
     * the lock on the main thread, inside launch, keeps the order.
     */
    @Test
    fun `a write launched first takes the lock first, whatever order its scope runs them in`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = LastFirstDispatcher()
        val landed = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val writes = ReaderProgressWrites(CoroutineScope(scope))

        writes.launch {
            gate.await()
            landed += 1
        }
        writes.launch { landed += 2 }
        gate.complete(Unit)
        scope.runAll()

        landed shouldBe listOf(1, 2)
    }

    /** A Main that posts stands in for a caller off the main thread; one that runs at once, for a caller on it. */
    @Test
    fun `a write launched off the main thread still lands before one launched after it on main`() = runTest {
        val landed = mutableListOf<Int>()
        val writes = ReaderProgressWrites(this)

        writes.launch { landed += 1 }
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        writes.launch { landed += 2 }
        advanceUntilIdle()

        landed shouldBe listOf(1, 2)
    }

    @Test
    fun `awaiting the writes returns only once the one in flight has landed`() = runTest {
        var landed = false
        val writes = ReaderProgressWrites(this)
        writes.launch {
            delay(100)
            landed = true
        }
        runCurrent()

        writes.awaitLanded()

        landed shouldBe true
    }
}

/** Holds what is dispatched to it, then runs the latest first. */
private class LastFirstDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued.addLast(block)
    }

    fun runAll() {
        while (queued.isNotEmpty()) queued.removeLast().run()
    }
}
