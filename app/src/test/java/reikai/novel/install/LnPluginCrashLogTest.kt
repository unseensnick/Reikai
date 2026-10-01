package reikai.novel.install

import io.kotest.matchers.collections.shouldHaveSingleElement
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginException

class LnPluginCrashLogTest {

    /** The crash screen's process has loaded nothing, so it has no failure to list until it loads. */
    @Test
    fun `a crash log lists a plugin that fails to load in a process that never loaded the plugins`() = runTest {
        val harness = LnPluginHarness(setOf("https://repo.test/broken.js"))
        coEvery { harness.host.loadPlugin(any(), any(), any(), any()) } throws LnPluginException("broken plugin")

        val entries = novelPluginCrashLogEntries(harness.installer)

        entries.shouldHaveSingleElement { it.startsWith("- broken (novel plugin)") }
    }
}
