package reikai.novel.install

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginException

/** Every novel screen waits on the on-open pass, so a plugin that keeps failing must not be read on each one. */
class LnPluginRetryWaitTest {

    private val harness = LnPluginHarness(setOf("https://repo.test/broken.js"))
    private var clock = 0L
    private var reads = 0

    init {
        harness.installer.now = { clock }
        coEvery { harness.host.inspectPlugin(any(), any(), any(), any(), any()) } answers {
            reads++
            throw LnPluginException("broken plugin")
        }
    }

    @Test
    fun `a plugin that just failed is not read again by the next screen's pass`() = runTest {
        harness.installer.ensureLoaded()

        harness.installer.ensureLoaded()

        reads shouldBe 1
    }

    @Test
    fun `a plugin that failed a minute ago is read again`() = runTest {
        harness.installer.ensureLoaded()
        clock += 60_000L

        harness.installer.ensureLoaded()

        reads shouldBe 2
    }

    @Test
    fun `a manual reload reads a plugin that just failed`() = runTest {
        harness.installer.ensureLoaded()

        harness.installer.loadInstalled()

        reads shouldBe 2
    }
}
