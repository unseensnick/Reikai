package reikai.data.work

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A job that cannot start is shown once per run, however many periods it fails in. */
class WorkerStartFailuresTest {

    private val shown = mutableListOf<String>()
    private val tags = mutableListOf<String>()
    private val failures = WorkerStartFailures { tag, name ->
        tags += tag
        shown += name
    }

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

    /** The platform replaces a notice posted under the same tag and id, so each job needs its own tag. */
    @Test
    fun `two jobs of the same name are shown under different tags`() {
        failures.report("a.UpdateJob", IllegalStateException())
        failures.report("b.UpdateJob", IllegalStateException())

        tags shouldBe listOf("a.UpdateJob", "b.UpdateJob")
    }
}
