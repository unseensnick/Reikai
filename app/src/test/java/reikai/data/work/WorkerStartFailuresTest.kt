package reikai.data.work

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A job that cannot start is shown once per run, however many periods it fails in. */
class WorkerStartFailuresTest {

    private val shown = mutableListOf<String>()
    private val failures = WorkerStartFailures { shown += it }

    @Test
    fun `a job failing every period is shown once, by its class name`() {
        failures.report("eu.kanade.tachiyomi.data.library.LibraryUpdateJob", IllegalStateException())
        failures.report("eu.kanade.tachiyomi.data.library.LibraryUpdateJob", IllegalStateException())

        shown shouldBe listOf("LibraryUpdateJob")
    }

    @Test
    fun `two jobs that cannot start are both shown`() {
        failures.report("a.LibraryUpdateJob", IllegalStateException())
        failures.report("b.NovelUpdateJob", IllegalStateException())

        shown shouldBe listOf("LibraryUpdateJob", "NovelUpdateJob")
    }
}
