package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A dev build's crash handler keeps a heap dump of an out-of-memory death, then crashes as before. */
class OomHeapDumpTest {

    private var dumps = 0
    private var forwarded: Throwable? = null
    private val handler = heapDumpOnOom({ _, e -> forwarded = e }) { dumps++ }

    @Test
    fun `a crash caused by running out of memory dumps the heap`() {
        handler.uncaughtException(Thread.currentThread(), RuntimeException(OutOfMemoryError()))
        dumps shouldBe 1
    }

    @Test
    fun `any other crash does not`() {
        handler.uncaughtException(Thread.currentThread(), IllegalStateException())
        dumps shouldBe 0
    }

    @Test
    fun `the crash still reaches the handler it replaced`() {
        val crash = RuntimeException(OutOfMemoryError())
        handler.uncaughtException(Thread.currentThread(), crash)
        forwarded shouldBe crash
    }

    @Test
    fun `a dump that fails still lets the crash through`() {
        val crash = RuntimeException(OutOfMemoryError())
        heapDumpOnOom({ _, e -> forwarded = e }) { error("no space") }
            .uncaughtException(Thread.currentThread(), crash)
        forwarded shouldBe crash
    }
}
