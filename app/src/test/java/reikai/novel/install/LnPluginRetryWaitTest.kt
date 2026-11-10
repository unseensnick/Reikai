package reikai.novel.install

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.novel.host.LnPluginException

/** Every novel screen waits on the on-open pass, so a plugin that keeps failing must not be read on each one. */
class LnPluginRetryWaitTest {

    private val harness = LnPluginHarness(setOf(BROKEN, OTHER))
    private var clock = 0L
    private val reads = mutableListOf<String>()

    init {
        harness.prefs.installedPluginMetadata().set(
            mapOf(BROKEN to LnInstalledPluginMetadata("broken"), OTHER to LnInstalledPluginMetadata("other")),
        )
        harness.installer.now = { clock }
        coEvery { harness.loader.installed(BROKEN) } returns "broken source"
        coEvery { harness.loader.installed(OTHER) } returns "other source"
        coEvery { harness.host.inspectPlugin(any(), any(), any(), any(), any()) } answers {
            reads += firstArg<String>()
            throw LnPluginException("broken plugin")
        }
    }

    @Test
    fun `a plugin that just failed is not read again by the next screen's pass`() = runTest {
        harness.installer.ensureLoaded()

        harness.installer.ensureLoaded()

        reads.count { it == "broken source" } shouldBe 1
    }

    @Test
    fun `a plugin that failed a minute ago is read again`() = runTest {
        harness.installer.ensureLoaded()
        clock += 60_000L

        harness.installer.ensureLoaded()

        reads.count { it == "broken source" } shouldBe 2
    }

    @Test
    fun `a manual reload reads a plugin that just failed`() = runTest {
        harness.installer.ensureLoaded()

        harness.installer.loadInstalled()

        reads.count { it == "broken source" } shouldBe 2
    }

    @Test
    fun `a Retry on a plugin that just failed reads it again`() = runTest {
        harness.installer.ensureLoaded()

        harness.installer.retryLoad("broken")

        reads.count { it == "broken source" } shouldBe 2
    }

    @Test
    fun `a Retry on one plugin leaves another that just failed to its wait`() = runTest {
        harness.installer.ensureLoaded()

        harness.installer.retryLoad("broken")

        reads.count { it == "other source" } shouldBe 1
    }

    private companion object {
        const val BROKEN = "https://repo.test/broken.js"
        const val OTHER = "https://repo.test/other.js"
    }
}
